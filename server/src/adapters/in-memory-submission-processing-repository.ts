import type { BackgroundJob, ScreenshotSubmission } from "../domain/model.ts";
import type { ValidatedAnalysis } from "../extraction/extraction-output.ts";
import type { InMemoryStore } from "./in-memory-store.ts";
import type { SubmissionProcessingRepository } from "./submission-processing-repository.ts";

export class InMemorySubmissionProcessingRepository implements SubmissionProcessingRepository {
  constructor(private readonly store: InMemoryStore) {}

  async begin(accountId: string, submissionId: string, now = new Date()) {
    const current = this.store.submissions.get(submissionId);
    if (!current || current.accountId !== accountId || current.status === "deleted") return undefined;
    const submission: ScreenshotSubmission = current.status === "uploaded"
      ? { ...current, status: "processing", processingStartedAt: now.toISOString(), updatedAt: now.toISOString() }
      : current;
    this.store.submissions.set(submissionId, submission);
    const extraction = this.store.extractions.get(submissionId);
    return { submission: structuredClone(submission), ...(extraction ? { extraction: structuredClone(extraction) } : {}) };
  }

  async save(accountId: string, submissionId: string, analysis: ValidatedAnalysis): Promise<void> {
    const submission = this.store.submissions.get(submissionId);
    if (!submission || submission.accountId !== accountId || submission.status !== "processing") throw new Error("submission_not_processing");
    if (!this.store.extractions.has(submissionId)) this.store.extractions.set(submissionId, structuredClone(analysis.extraction));
    for (const card of analysis.cards) if (!this.store.cards.has(card.id)) this.store.cards.set(card.id, structuredClone(card));
    for (const memory of analysis.memories) if (!this.store.memories.has(memory.id)) this.store.memories.set(memory.id, structuredClone(memory));
  }

  async finalize(accountId: string, submissionId: string, result: "ready" | "failed", failureCode?: string, now = new Date()) {
    const current = this.store.submissions.get(submissionId);
    if (!current || current.accountId !== accountId || current.status === "deleted") return undefined;
    const finalized: ScreenshotSubmission = {
      ...current,
      status: result,
      ...(result === "failed" ? { failureCode: failureCode ?? "analysis_failed" } : {}),
      completedAt: now.toISOString(),
      updatedAt: now.toISOString(),
    };
    this.store.submissions.set(submissionId, finalized);
    return structuredClone(finalized);
  }

  async enqueueCleanup(accountId: string, submissionId: string, job: BackgroundJob): Promise<void> {
    const existing = [...this.store.jobs.values()].find((item) => item.accountId === accountId && item.type === job.type && item.idempotencyKey === job.idempotencyKey);
    if (!existing) this.store.jobs.set(job.id, structuredClone(job));
  }
}
