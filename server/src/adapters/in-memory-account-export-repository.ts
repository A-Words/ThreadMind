import type { InMemoryStore } from "./in-memory-store.ts";
import { exportedSubmission, type AccountExport, type AccountExportRepository } from "./account-export-repository.ts";

export class InMemoryAccountExportRepository implements AccountExportRepository {
  constructor(private readonly store: InMemoryStore) {}

  async create(accountId: string, now = new Date()): Promise<AccountExport> {
    return {
      format: "threadmind-export-v1",
      generatedAt: now.toISOString(),
      accountId,
      submissions: [...this.store.submissions.values()]
        .filter((item) => item.accountId === accountId)
        .map(exportedSubmission),
      extractions: [...this.store.extractions.values()]
        .filter((item) => item.accountId === accountId)
        .map((item) => structuredClone(item)),
      actionCards: [...this.store.cards.values()]
        .filter((item) => item.accountId === accountId)
        .map((item) => structuredClone(item)),
      actionReceipts: this.store.receipts
        .filter((item) => item.accountId === accountId)
        .map((item) => structuredClone(item)),
      memories: [...this.store.memories.values()]
        .filter((item) => item.accountId === accountId)
        .map((item) => structuredClone(item)),
      insights: [...this.store.insights.values()]
        .filter((item) => item.accountId === accountId)
        .map((item) => structuredClone(item)),
    };
  }
}
