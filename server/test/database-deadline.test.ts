import assert from "node:assert/strict";
import { once } from "node:events";
import { createServer, type Socket } from "node:net";
import { setTimeout as delay } from "node:timers/promises";
import { describe, it } from "node:test";
import { Kysely, PostgresDialect, sql } from "kysely";
import pg from "pg";
import { createDatabase } from "../src/database/database.ts";
import { DatabaseQueryTimeoutError, withQueryDeadline } from "../src/database/deadline-pool.ts";

// A minimal PostgreSQL wire peer, not a mocked query promise: this exercises pg's
// active query, transaction rollback, socket destruction and pool replacement.
async function fixture(stall?: string) {
  const sockets = new Set<Socket>();
  const queries: string[] = [];
  let connections = 0;
  let stalls = 0;
  const server = createServer((socket) => {
    connections += 1;
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
    socket.on("error", () => {});
    let incoming = Buffer.alloc(0);
    let startup = true;
    socket.on("data", (chunk) => {
      incoming = Buffer.concat([incoming, chunk]);
      while (incoming.length >= (startup ? 4 : 5)) {
        const size = incoming.readInt32BE(startup ? 0 : 1) + (startup ? 0 : 1);
        if (incoming.length < size) return;
        const frame = incoming.subarray(0, size);
        incoming = incoming.subarray(size);
        if (startup) {
          startup = false;
          socket.write(Buffer.concat([packet("R", Buffer.alloc(4)), packet("Z", Buffer.from("I"))]));
          continue;
        }
        if (frame[0] === 88) { socket.end(); continue; } // Terminate
        assert.equal(frame[0], 81, "only simple Query messages expected");
        const query = frame.subarray(5, -1).toString();
        queries.push(query);
        if (query === stall && stalls++ === 0) continue;
        if (query === "INVALID") {
          socket.write(Buffer.concat([
            packet("E", Buffer.from("SERROR\0C42601\0Msynthetic syntax error\0\0")),
            packet("Z", Buffer.from("E")),
          ]));
        } else {
          socket.write(Buffer.concat([
            packet("C", Buffer.from(`${query.startsWith("select") ? "SELECT 1" : query.toUpperCase()}\0`)),
            packet("Z", Buffer.from(query === "begin" ? "T" : "I")),
          ]));
        }
      }
    });
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const address = server.address();
  assert.ok(address && typeof address !== "string");
  const pool = new pg.Pool({ host: "127.0.0.1", port: address.port, user: "test", database: "test", ssl: false, max: 1, connectionTimeoutMillis: 1000 });
  const guarded = withQueryDeadline(pool, 200);
  const database = new Kysely<object>({ dialect: new PostgresDialect({ pool: guarded }) });
  return {
    database, pool, guarded, queries,
    connections: () => connections,
    disconnect: () => { for (const socket of sockets) socket.destroy(); },
    async close() {
      await database.destroy();
      // Some tests acquire directly through the adapter, without initializing Kysely.
      if (!pool.ending) await pool.end();
      for (const socket of sockets) socket.destroy();
      await new Promise<void>((resolve) => server.close(() => resolve()));
    },
  };
}

function packet(type: string, body: Buffer): Buffer {
  const header = Buffer.alloc(5);
  header.write(type);
  header.writeInt32BE(body.length + 4, 1);
  return Buffer.concat([header, body]);
}

describe("Database query deadlines", { timeout: 5000 }, () => {
  for (const stalledQuery of ["begin", "select 1", "commit"]) {
    it(`evicts a socket stalled at ${stalledQuery} and permits the next transaction`, async () => {
      const f = await fixture(stalledQuery);
      try {
        const operation = () => f.database.transaction().execute((tx) => sql`select 1`.execute(tx));
        await assert.rejects(operation(), DatabaseQueryTimeoutError);
        assert.equal(f.pool.totalCount, 0, "the stalled connection must not return to the pool");
        assert.equal(f.queries.filter((query) => query === stalledQuery).length, 1, "no automatic replay, including uncertain COMMIT");
        assert.equal(f.queries.includes("rollback"), false, "rollback must fail fast on the discarded connection");
        await operation();
        assert.equal(f.connections(), 2);
        assert.equal(f.pool.idleCount, 1);
      } finally { await f.close(); }
    });
  }

  it("rolls back ordinary SQL errors and reuses the healthy connection", async () => {
    const f = await fixture();
    try {
      await assert.rejects(f.database.transaction().execute((tx) => sql.raw("INVALID").execute(tx)), { code: "42601" });
      assert.ok(f.queries.includes("rollback"));
      await sql`select 1`.execute(f.database);
      assert.equal(f.connections(), 1);
    } finally { await f.close(); }
  });

  it("does not release twice or let a discarded handle query a replacement connection", async () => {
    const f = await fixture("select 1");
    try {
      const old = await f.guarded.connect();
      await assert.rejects(old.query("select 1", []), DatabaseQueryTimeoutError);
      old.release();
      const replacement = await f.guarded.connect();
      await assert.rejects(old.query("commit", []), DatabaseQueryTimeoutError);
      old.release();
      await replacement.query("select 1", []);
      replacement.release();
      assert.equal(f.pool.idleCount, 1);
      assert.equal(f.connections(), 2);
    } finally { await f.close(); }
  });

  it("validates the shared API/Worker response deadline before connecting", async () => {
    for (const value of ["0", "999", "120001", "NaN", "35000.5"]) {
      assert.throws(() => createDatabase({ DATABASE_URL: "postgresql://test", DATABASE_QUERY_TIMEOUT_MS: value }), /DATABASE_QUERY_TIMEOUT_MS/);
    }
    for (const value of [undefined, "1000", "35000", "120000"]) {
      const env: NodeJS.ProcessEnv = { DATABASE_URL: "postgresql://test" };
      if (value !== undefined) env.DATABASE_QUERY_TIMEOUT_MS = value;
      await createDatabase(env).destroy();
    }
  });

  it("clears the timer after success instead of later evicting a healthy connection", async () => {
    const f = await fixture();
    try {
      await sql`select 1`.execute(f.database);
      await delay(250);
      await sql`select 1`.execute(f.database);
      assert.equal(f.connections(), 1);
    } finally { await f.close(); }
  });

  it("handles a connection lost between queries without crashing the Worker", async () => {
    const f = await fixture();
    try {
      const client = await f.guarded.connect();
      await client.query("select 1", []);
      const removed = once(f.pool, "remove");
      f.disconnect();
      await removed;
      await assert.rejects(client.query("commit", []), /Connection terminated unexpectedly/);
      client.release();
      await sql`select 1`.execute(f.database);
      assert.equal(f.connections(), 2);
    } finally { await f.close(); }
  });
});
