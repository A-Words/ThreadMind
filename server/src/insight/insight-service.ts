import type { InsightRepository } from "../adapters/insight-repository.ts";
import type { MemoryRepository } from "../adapters/memory-repository.ts";
import { createInsightBundle } from "../domain/insight.ts";
import type { ActionCard, ActionReceipt, InsightBundle } from "../domain/model.ts";
import type { InsightGenerator } from "./insight-generator.ts";

export class InsightService {
  constructor(
    private readonly repository: InsightRepository,
    private readonly memories: MemoryRepository,
    private readonly generator: InsightGenerator,
  ) {}

  async ensureForReceipt(card: ActionCard, receipt: ActionReceipt): Promise<InsightBundle | undefined> {
    if (receipt.status !== "succeeded" || !receipt.targetRecordId) return undefined;
    const generationKey = `receipt:${receipt.id}`;
    const existing = await this.repository.findByGenerationKey(receipt.accountId, generationKey);
    if (existing) return existing;
    const generated = await this.generator.generate({
      card,
      receipt: { ...receipt, status: "succeeded", targetRecordId: receipt.targetRecordId },
      memories: await this.memories.listActive(receipt.accountId),
    });
    const bundle = createInsightBundle({
      accountId: receipt.accountId,
      submissionId: card.submissionId,
      receipts: [receipt],
      items: generated.items,
      modelTrace: generated.modelTrace,
    });
    return this.repository.create(bundle, generationKey);
  }
}
