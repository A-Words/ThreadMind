import { Kysely, PostgresDialect } from "kysely";
import pg from "pg";
import { withQueryDeadline } from "./deadline-pool.ts";
import type { ThreadMindDatabase } from "./schema.ts";

const { Pool } = pg;

export function createDatabase(env: NodeJS.ProcessEnv, applicationName = "threadmind-api"): Kysely<ThreadMindDatabase> {
  const connectionString = env.DATABASE_URL;
  if (!connectionString) throw new Error("DATABASE_URL is required");
  const max = Number(env.DATABASE_POOL_MAX ?? 5);
  if (!Number.isInteger(max) || max < 1 || max > 20) throw new Error("DATABASE_POOL_MAX must be an integer between 1 and 20");
  const queryTimeoutMs = Number(env.DATABASE_QUERY_TIMEOUT_MS ?? 35_000);
  if (!Number.isInteger(queryTimeoutMs) || queryTimeoutMs < 1_000 || queryTimeoutMs > 120_000) {
    throw new Error("DATABASE_QUERY_TIMEOUT_MS must be an integer between 1000 and 120000");
  }
  const rejectUnauthorized = env.DATABASE_SSL_REJECT_UNAUTHORIZED !== "false";
  const pool = new Pool({
    connectionString,
    ssl: { rejectUnauthorized },
    max,
    idleTimeoutMillis: 30_000,
    connectionTimeoutMillis: 10_000,
    keepAlive: true,
    keepAliveInitialDelayMillis: 10_000,
    application_name: applicationName,
  });
  // pg already removes idle broken clients. Handle the event without logging credentials/SQL.
  pool.on("error", () => console.error("threadmind_database_idle_connection_error", { code: "connection_lost" }));
  return new Kysely<ThreadMindDatabase>({
    dialect: new PostgresDialect({
      controlClient: pg.Client,
      pool: withQueryDeadline(pool, queryTimeoutMs),
    }),
  });
}
