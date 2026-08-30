import { sql, type Kysely, type Transaction } from "kysely";
import type { ThreadMindDatabase } from "./schema.ts";

export type AccountTransaction = Transaction<ThreadMindDatabase>;

export async function withAccount<T>(
  database: Kysely<ThreadMindDatabase>,
  accountId: string,
  operation: (transaction: AccountTransaction) => Promise<T>,
): Promise<T> {
  return database.transaction().execute(async (transaction) => {
    await sql`set local role threadmind_api`.execute(transaction);
    await sql`select set_config('app.current_account_id', ${accountId}, true)`.execute(transaction);
    await sql`set local statement_timeout = '5s'`.execute(transaction);
    return operation(transaction);
  });
}

export async function retryTransient<T>(operation: () => Promise<T>): Promise<T> {
  try {
    return await operation();
  } catch (error) {
    if (!isTransientDatabaseError(error)) throw error;
    return operation();
  }
}

function isTransientDatabaseError(error: unknown): boolean {
  if (!(error instanceof Error)) return false;
  const code = "code" in error && typeof error.code === "string" ? error.code : "";
  return code.startsWith("08") || ["57P01", "57P02", "57P03", "ECONNRESET", "EPIPE", "ETIMEDOUT"].includes(code)
    || /connection (?:terminated|closed|reset)|socket hang up/i.test(error.message);
}
