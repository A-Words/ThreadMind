import { sql, type Kysely } from "kysely";
import { DomainError } from "../domain/errors.ts";
import { withAccount } from "../database/account-transaction.ts";
import type { ThreadMindDatabase } from "../database/schema.ts";

export interface SensitiveSessionVerifier {
  verify(accountId: string, sessionId: string | undefined): Promise<void>;
}

export class KyselySensitiveSessionVerifier implements SensitiveSessionVerifier {
  constructor(private readonly database: Kysely<ThreadMindDatabase>) {}

  async verify(accountId: string, sessionId: string | undefined): Promise<void> {
    if (!sessionId) throw new DomainError("unauthorized", "An active Supabase session is required");
    const result = await withAccount(this.database, accountId, async (transaction) =>
      sql<{ active: boolean }>`select threadmind.is_active_session(${accountId}::uuid, ${sessionId}::uuid) as active`
        .execute(transaction),
    );
    if (result.rows[0]?.active !== true) throw new DomainError("unauthorized", "The Supabase session is no longer active");
  }
}

export class InMemorySensitiveSessionVerifier implements SensitiveSessionVerifier {
  readonly checks: Array<{ accountId: string; sessionId: string | undefined }> = [];

  constructor(private readonly isActive: (accountId: string, sessionId: string | undefined) => boolean = () => true) {}

  async verify(accountId: string, sessionId: string | undefined): Promise<void> {
    this.checks.push({ accountId, sessionId });
    if (!this.isActive(accountId, sessionId)) throw new DomainError("unauthorized", "The Supabase session is no longer active");
  }
}
