import type { Insertable, Kysely, Selectable } from "kysely";
import { recordExecution } from "../domain/action-card.ts";
import type { ActionCard, ActionReceipt } from "../domain/model.ts";
import { retryTransient, withAccount } from "../database/account-transaction.ts";
import type { ActionCardsTable, ActionReceiptsTable, ThreadMindDatabase } from "../database/schema.ts";
import type { ActionRepository, ExecutionResult } from "./action-repository.ts";

export class KyselyActionRepository implements ActionRepository {
  constructor(private readonly database: Kysely<ThreadMindDatabase>) {}

  async create(card: ActionCard): Promise<ActionCard> {
    return retryTransient(() => withAccount(this.database, card.accountId, async (transaction) => {
      const existing = await transaction
        .selectFrom("threadmind.action_cards")
        .selectAll()
        .where("id", "=", card.id)
        .executeTakeFirst();
      if (existing) return toActionCard(existing);
      const row = await transaction
        .insertInto("threadmind.action_cards")
        .values(toActionCardRow(card))
        .returningAll()
        .executeTakeFirstOrThrow();
      return toActionCard(row);
    }));
  }

  async find(accountId: string, id: string): Promise<ActionCard | undefined> {
    return retryTransient(() => withAccount(this.database, accountId, async (transaction) => {
      const row = await transaction
        .selectFrom("threadmind.action_cards")
        .selectAll()
        .where("id", "=", id)
        .executeTakeFirst();
      return row ? toActionCard(row) : undefined;
    }));
  }

  async listForSubmission(accountId: string, submissionId: string): Promise<ActionCard[]> {
    return retryTransient(() => withAccount(this.database, accountId, async (transaction) => {
      const rows = await transaction
        .selectFrom("threadmind.action_cards")
        .selectAll()
        .where("submission_id", "=", submissionId)
        .orderBy("created_at")
        .orderBy("id")
        .execute();
      return rows.map(toActionCard);
    }));
  }

  async mutate(accountId: string, id: string, transition: (card: ActionCard) => ActionCard): Promise<ActionCard | undefined> {
    return withAccount(this.database, accountId, async (transaction) => {
      const current = await transaction
        .selectFrom("threadmind.action_cards")
        .selectAll()
        .where("id", "=", id)
        .forUpdate()
        .executeTakeFirst();
      if (!current) return undefined;
      const updated = transition(toActionCard(current));
      const row = await transaction
        .updateTable("threadmind.action_cards")
        .set(toActionCardUpdate(updated))
        .where("id", "=", id)
        .returningAll()
        .executeTakeFirstOrThrow();
      return toActionCard(row);
    });
  }

  async recordExecution(accountId: string, cardId: string, receiptId: string, result: ExecutionResult) {
    return retryTransient(() => withAccount(this.database, accountId, async (transaction) => {
      const existingReceipt = await transaction
        .selectFrom("threadmind.action_receipts")
        .selectAll()
        .where("id", "=", receiptId)
        .where("action_card_id", "=", cardId)
        .executeTakeFirst();
      if (existingReceipt) {
        const currentCard = await transaction
          .selectFrom("threadmind.action_cards")
          .selectAll()
          .where("id", "=", cardId)
          .executeTakeFirst();
        return currentCard
          ? { card: toActionCard(currentCard), receipt: toActionReceipt(existingReceipt) }
          : undefined;
      }

      const currentCard = await transaction
        .selectFrom("threadmind.action_cards")
        .selectAll()
        .where("id", "=", cardId)
        .forUpdate()
        .executeTakeFirst();
      if (!currentCard) return undefined;
      const previousReceipts = await transaction
        .selectFrom("threadmind.action_receipts")
        .selectAll()
        .where("action_card_id", "=", cardId)
        .orderBy("attempt")
        .execute();
      const recorded = recordExecution(
        toActionCard(currentCard),
        result,
        previousReceipts.map(toActionReceipt),
        new Date(),
        receiptId,
      );
      const updatedCard = await transaction
        .updateTable("threadmind.action_cards")
        .set(toActionCardUpdate(recorded.card))
        .where("id", "=", cardId)
        .returningAll()
        .executeTakeFirstOrThrow();
      const receipt = await transaction
        .insertInto("threadmind.action_receipts")
        .values(toActionReceiptRow(recorded.receipt))
        .returningAll()
        .executeTakeFirstOrThrow();
      return { card: toActionCard(updatedCard), receipt: toActionReceipt(receipt) };
    }));
  }
}

function toActionCardRow(card: ActionCard): Insertable<ActionCardsTable> {
  return {
    id: card.id,
    account_id: card.accountId,
    submission_id: card.submissionId,
    action_type: card.type,
    version: card.version,
    fields: JSON.stringify(card.fields),
    evidence: JSON.stringify(card.evidence),
    field_confidence: JSON.stringify(card.fieldConfidence),
    validation_issues: JSON.stringify(card.validationIssues),
    target_account_id: card.targetAccountId ?? null,
    status: card.status,
    blockers: JSON.stringify(card.blockers),
    confirmed_snapshot: card.confirmedSnapshot ? JSON.stringify(card.confirmedSnapshot) : null,
    confirmed_at: card.confirmedAt ?? null,
  };
}

function toActionCardUpdate(card: ActionCard) {
  return {
    version: card.version,
    fields: JSON.stringify(card.fields),
    evidence: JSON.stringify(card.evidence),
    field_confidence: JSON.stringify(card.fieldConfidence),
    validation_issues: JSON.stringify(card.validationIssues),
    target_account_id: card.targetAccountId ?? null,
    status: card.status,
    blockers: JSON.stringify(card.blockers),
    confirmed_snapshot: card.confirmedSnapshot ? JSON.stringify(card.confirmedSnapshot) : null,
    confirmed_at: card.confirmedAt ?? null,
    updated_at: new Date(),
  };
}

export function toActionCard(row: Selectable<ActionCardsTable>): ActionCard {
  return {
    id: row.id,
    accountId: row.account_id,
    submissionId: row.submission_id,
    type: row.action_type,
    version: row.version,
    fields: row.fields,
    evidence: row.evidence,
    fieldConfidence: row.field_confidence,
    validationIssues: row.validation_issues,
    ...(row.target_account_id ? { targetAccountId: row.target_account_id } : {}),
    status: row.status,
    blockers: row.blockers,
    ...(row.confirmed_snapshot ? { confirmedSnapshot: row.confirmed_snapshot } : {}),
    ...(row.confirmed_at ? { confirmedAt: new Date(row.confirmed_at).toISOString() } : {}),
  };
}

function toActionReceiptRow(receipt: ActionReceipt): Insertable<ActionReceiptsTable> {
  return {
    id: receipt.id,
    account_id: receipt.accountId,
    action_card_id: receipt.actionCardId,
    confirmed_version: receipt.confirmedVersion,
    attempt: receipt.attempt,
    status: receipt.status,
    provider: receipt.provider,
    target_record_id: receipt.targetRecordId ?? null,
    error_code: receipt.errorCode ?? null,
    error_message: receipt.errorMessage ?? null,
    started_at: receipt.startedAt,
    completed_at: receipt.completedAt,
  };
}

export function toActionReceipt(row: Selectable<ActionReceiptsTable>): ActionReceipt {
  return {
    id: row.id,
    accountId: row.account_id,
    actionCardId: row.action_card_id,
    confirmedVersion: row.confirmed_version,
    attempt: row.attempt,
    status: row.status,
    provider: row.provider,
    ...(row.target_record_id ? { targetRecordId: row.target_record_id } : {}),
    ...(row.error_code ? { errorCode: row.error_code } : {}),
    ...(row.error_message ? { errorMessage: row.error_message } : {}),
    startedAt: new Date(row.started_at).toISOString(),
    completedAt: new Date(row.completed_at).toISOString(),
  };
}
