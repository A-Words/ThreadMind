import { DomainError } from "../domain/errors.ts";
import type { BackgroundJob, ScreenshotSubmission } from "../domain/model.ts";
import { sameSubmissionContent } from "../domain/submission.ts";
import type { InMemoryStore } from "./in-memory-store.ts";
import type { SubmissionRepository } from "./submission-repository.ts";

export class InMemorySubmissionRepository implements SubmissionRepository {
  constructor(private readonly store: InMemoryStore) {}

  async find(accountId: string, id: string): Promise<ScreenshotSubmission | undefined> {
    const submission = this.store.submissions.get(id);
    return submission?.accountId === accountId ? structuredClone(submission) : undefined;
  }

  async createWithJob(submission: ScreenshotSubmission, job: BackgroundJob): Promise<ScreenshotSubmission> {
    const existing = await this.find(submission.accountId, submission.id);
    if (existing) {
      if (!sameSubmissionContent(existing, submission)) throw new DomainError("submission_conflict", "Submission id already has different content");
      return existing;
    }
    this.store.submissions.set(submission.id, structuredClone(submission));
    this.store.jobs.set(job.id, structuredClone(job));
    return structuredClone(submission);
  }

  async remove(accountId: string, id: string): Promise<boolean> {
    const submission = await this.find(accountId, id);
    if (!submission) return false;
    this.store.submissions.delete(id);
    for (const [jobId, job] of this.store.jobs) if (job.accountId === accountId && job.aggregateId === id) this.store.jobs.delete(jobId);
    for (const [extractionId, extraction] of this.store.extractions) {
      if (extraction.accountId === accountId && extraction.submissionId === id) this.store.extractions.delete(extractionId);
    }
    const cardIds = new Set<string>();
    for (const [cardId, card] of this.store.cards) {
      if (card.accountId === accountId && card.submissionId === id) {
        cardIds.add(cardId);
        this.store.cards.delete(cardId);
      }
    }
    for (let index = this.store.receipts.length - 1; index >= 0; index -= 1) {
      const receipt = this.store.receipts[index]!;
      if (receipt.accountId === accountId && cardIds.has(receipt.actionCardId)) this.store.receipts.splice(index, 1);
    }
    const insightIds = new Set<string>();
    for (const [insightId, insight] of this.store.insights) {
      if (insight.accountId === accountId && insight.submissionId === id) {
        insightIds.add(insightId);
        this.store.insights.delete(insightId);
      }
    }
    for (const [generationKey, insightId] of this.store.insightGenerationKeys) {
      if (insightIds.has(insightId)) this.store.insightGenerationKeys.delete(generationKey);
    }
    return true;
  }
}
