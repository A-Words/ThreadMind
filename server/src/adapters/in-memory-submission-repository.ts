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
}
