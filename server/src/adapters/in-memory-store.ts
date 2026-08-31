import type { ActionCard, ActionReceipt, BackgroundJob, ContextExtraction, InsightBundle, MemoryRecord, ScreenshotSubmission } from "../domain/model.ts";

export class InMemoryStore {
  readonly cards = new Map<string, ActionCard>();
  readonly receipts: ActionReceipt[] = [];
  readonly memories = new Map<string, MemoryRecord>();
  readonly insights = new Map<string, InsightBundle>();
  readonly insightGenerationKeys = new Map<string, string>();
  readonly submissions = new Map<string, ScreenshotSubmission>();
  readonly jobs = new Map<string, BackgroundJob>();
  readonly extractions = new Map<string, ContextExtraction>();

  card(accountId: string, id: string): ActionCard | undefined {
    const card = this.cards.get(id);
    return card?.accountId === accountId ? structuredClone(card) : undefined;
  }

  activeMemories(accountId: string): MemoryRecord[] {
    return [...this.memories.values()].filter((memory) => memory.accountId === accountId && memory.status === "active").map((memory) => structuredClone(memory));
  }

  deleteAccount(accountId: string): void {
    for (const [id, card] of this.cards) if (card.accountId === accountId) this.cards.delete(id);
    for (let index = this.receipts.length - 1; index >= 0; index -= 1) {
      if (this.receipts[index]?.accountId === accountId) this.receipts.splice(index, 1);
    }
    for (const [id, memory] of this.memories) if (memory.accountId === accountId) this.memories.delete(id);
    for (const [id, insight] of this.insights) if (insight.accountId === accountId) this.insights.delete(id);
    for (const [key] of this.insightGenerationKeys) if (key.startsWith(`${accountId}:`)) this.insightGenerationKeys.delete(key);
    for (const [id, submission] of this.submissions) if (submission.accountId === accountId) this.submissions.delete(id);
    for (const [id, job] of this.jobs) if (job.accountId === accountId) this.jobs.delete(id);
    for (const [id, extraction] of this.extractions) if (extraction.accountId === accountId) this.extractions.delete(id);
  }
}
