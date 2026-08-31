import type { Kysely } from "kysely";
import { retryTransient, withAccount } from "../database/account-transaction.ts";
import type { ThreadMindDatabase } from "../database/schema.ts";
import { toActionCard, toActionReceipt } from "./kysely-action-repository.ts";
import { toInsightBundle } from "./kysely-insight-repository.ts";
import { toMemoryRecord } from "./kysely-memory-repository.ts";
import { toExtraction } from "./kysely-submission-processing-repository.ts";
import { toSubmission } from "./kysely-submission-repository.ts";
import { exportedSubmission, type AccountExport, type AccountExportRepository } from "./account-export-repository.ts";

export class KyselyAccountExportRepository implements AccountExportRepository {
  constructor(private readonly database: Kysely<ThreadMindDatabase>) {}

  async create(accountId: string, now = new Date()): Promise<AccountExport> {
    return retryTransient(() => withAccount(this.database, accountId, async (transaction) => {
      const submissions = await transaction.selectFrom("threadmind.screenshot_submissions").selectAll()
        .orderBy("created_at", "asc").orderBy("id", "asc").execute();
      const extractions = await transaction.selectFrom("threadmind.context_extractions").selectAll()
        .orderBy("created_at", "asc").orderBy("id", "asc").execute();
      const actionCards = await transaction.selectFrom("threadmind.action_cards").selectAll()
        .orderBy("created_at", "asc").orderBy("id", "asc").execute();
      const actionReceipts = await transaction.selectFrom("threadmind.action_receipts").selectAll()
        .orderBy("started_at", "asc").orderBy("id", "asc").execute();
      const memories = await transaction.selectFrom("threadmind.memory_records").selectAll()
        .orderBy("created_at", "asc").orderBy("id", "asc").execute();
      const insights = await transaction.selectFrom("threadmind.insight_bundles").selectAll()
        .orderBy("generated_at", "asc").orderBy("id", "asc").execute();
      return {
        format: "threadmind-export-v1",
        generatedAt: now.toISOString(),
        accountId,
        submissions: submissions.map(toSubmission).map(exportedSubmission),
        extractions: extractions.map(toExtraction),
        actionCards: actionCards.map(toActionCard),
        actionReceipts: actionReceipts.map(toActionReceipt),
        memories: memories.map(toMemoryRecord),
        insights: insights.map(toInsightBundle),
      };
    }));
  }
}
