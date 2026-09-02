import type { PostgresCursor, PostgresPool, PostgresPoolClient, PostgresQueryResult } from "kysely";
import type pg from "pg";

export class DatabaseQueryTimeoutError extends Error {
  readonly code = "ETIMEDOUT";

  constructor() {
    super("Database query response timed out; connection discarded");
    this.name = "DatabaseQueryTimeoutError";
  }
}

/** A rejected query alone is insufficient: pg can otherwise retain a stalled socket. */
export function withQueryDeadline(pool: pg.Pool, timeoutMs: number): PostgresPool {
  return {
    options: pool.options,
    async connect() {
      return guardClient(await pool.connect(), timeoutMs);
    },
    end: () => pool.end(),
  };
}

function guardClient(client: pg.PoolClient, timeoutMs: number): PostgresPoolClient {
  const delegate: PostgresPoolClient = client;
  let released = false;
  let failure: Error | undefined;

  function release(destroy = false): void {
    if (released) return;
    released = true;
    client.removeListener("error", discard);
    // pg release(true) removes the client and closes its socket, including active queries.
    client.release(destroy);
  }

  function discard(error: Error): void {
    failure ??= error;
    release(true);
  }
  client.on("error", discard);

  function bounded<T>(operation: () => Promise<T>): Promise<T> {
    if (failure) return Promise.reject(failure);
    if (released) return Promise.reject(new Error("Database connection already released"));
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        const error = new DatabaseQueryTimeoutError();
        discard(error);
        reject(error);
      }, timeoutMs);
      // Attach both handlers so late responses/rejections after eviction are consumed.
      Promise.resolve().then(operation).then(resolve, reject).finally(() => clearTimeout(timer));
    });
  }

  function query<R>(sql: string, parameters: ReadonlyArray<unknown>): Promise<PostgresQueryResult<R>>;
  function query<R>(cursor: PostgresCursor<R>): PostgresCursor<R>;
  function query<R>(input: string | PostgresCursor<R>, parameters: ReadonlyArray<unknown> = []): Promise<PostgresQueryResult<R>> | PostgresCursor<R> {
    if (typeof input === "string") return bounded(() => delegate.query<R>(input, parameters));
    if (failure) throw failure;
    if (released) throw new Error("Database connection already released");
    const cursor = delegate.query(input);
    return {
      read: (count) => bounded(() => cursor.read(count)),
      close: () => bounded(() => cursor.close()),
    };
  }

  return {
    ...(delegate.processID === undefined ? {} : { processID: delegate.processID }),
    query,
    release: () => release(),
  };
}
