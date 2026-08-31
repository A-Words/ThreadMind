import { type Insertable, type Kysely, type Selectable } from "kysely";
import type { InsightBundle } from "../domain/model.ts";
import { retryTransient, withAccount } from "../database/account-transaction.ts";
import type { InsightBundlesTable, ThreadMindDatabase } from "../database/schema.ts";
import type { InsightRepository } from "./insight-repository.ts";

export class KyselyInsightRepository implements InsightRepository {
  constructor(private readonly database: Kysely<ThreadMindDatabase>) {}

  async findByGenerationKey(accountId: string, generationKey: string): Promise<InsightBundle | undefined> {
    return retryTransient(() => withAccount(this.database, accountId, async (trx) => {
      const row = await trx
        .selectFrom("threadmind.insight_bundles")
        .selectAll()
        .where("generation_key", "=", generationKey)
        .executeTakeFirst();
      return row ? toInsightBundle(row) : undefined;
    }));
  }

  async create(bundle: InsightBundle, generationKey: string): Promise<InsightBundle> {
    return retryTransient(() => withAccount(this.database, bundle.accountId, async (trx) => {
      const inserted = await trx
        .insertInto("threadmind.insight_bundles")
        .values(toInsightRow(bundle, generationKey))
        .onConflict((conflict) => conflict.columns(["account_id", "generation_key"]).doNothing())
        .returningAll()
        .executeTakeFirst();
      if (inserted) return toInsightBundle(inserted);
      const existing = await trx
        .selectFrom("threadmind.insight_bundles")
        .selectAll()
        .where("generation_key", "=", generationKey)
        .executeTakeFirstOrThrow();
      return toInsightBundle(existing);
    }));
  }

  async list(accountId: string, submissionId?: string): Promise<InsightBundle[]> {
    return retryTransient(() => withAccount(this.database, accountId, async (trx) => {
      let selection = trx.selectFrom("threadmind.insight_bundles").selectAll();
      if (submissionId) selection = selection.where("submission_id", "=", submissionId);
      const rows = await selection
        .orderBy("generated_at", "desc")
        .orderBy("id", "desc")
        .limit(100)
        .execute();
      return rows.map(toInsightBundle);
    }));
  }
}

function toInsightRow(bundle: InsightBundle, generationKey: string): Insertable<InsightBundlesTable> {
  return {
    id: bundle.id,
    account_id: bundle.accountId,
    submission_id: bundle.submissionId,
    generation_key: generationKey,
    action_receipt_ids: JSON.stringify(bundle.actionReceiptIds),
    items: JSON.stringify(bundle.items),
    model_trace: JSON.stringify(bundle.modelTrace),
    generated_at: bundle.generatedAt,
  };
}

function toInsightBundle(row: Selectable<InsightBundlesTable>): InsightBundle {
  return {
    id: row.id,
    accountId: row.account_id,
    submissionId: row.submission_id,
    actionReceiptIds: row.action_receipt_ids,
    items: row.items,
    generatedAt: new Date(row.generated_at).toISOString(),
    modelTrace: row.model_trace,
  };
}
