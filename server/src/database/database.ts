import { Kysely, PostgresDialect } from "kysely";
import pg from "pg";
import type { ThreadMindDatabase } from "./schema.ts";

const { Pool } = pg;

export function createDatabase(env: NodeJS.ProcessEnv): Kysely<ThreadMindDatabase> {
  const connectionString = env.DATABASE_URL;
  if (!connectionString) throw new Error("DATABASE_URL is required");
  const max = Number(env.DATABASE_POOL_MAX ?? 5);
  if (!Number.isInteger(max) || max < 1 || max > 20) throw new Error("DATABASE_POOL_MAX must be an integer between 1 and 20");
  const rejectUnauthorized = env.DATABASE_SSL_REJECT_UNAUTHORIZED !== "false";
  return new Kysely<ThreadMindDatabase>({
    dialect: new PostgresDialect({
      pool: new Pool({
        connectionString,
        ssl: { rejectUnauthorized },
        max,
        idleTimeoutMillis: 30_000,
        connectionTimeoutMillis: 10_000,
        application_name: "threadmind-api",
      }),
    }),
  });
}
