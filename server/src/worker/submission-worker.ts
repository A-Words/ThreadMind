import { randomUUID } from "node:crypto";
import type { BackgroundJob } from "../domain/model.ts";
import { validateExtractionOutput } from "../extraction/extraction-output.ts";
import type { VisionExtractionModel } from "../extraction/vision-extraction-model.ts";
import type { SubmissionProcessingRepository } from "../adapters/submission-processing-repository.ts";
import type { TemporaryImageStorage } from "../adapters/temporary-image-storage.ts";
import type { WorkerQueueRepository } from "../adapters/worker-queue-repository.ts";

export class SubmissionWorker {
  constructor(
    private readonly workerId: string,
    private readonly queue: WorkerQueueRepository,
    private readonly processing: SubmissionProcessingRepository,
    private readonly storage: TemporaryImageStorage,
    private readonly model: VisionExtractionModel,
  ) {}

  async runOne(now = new Date()): Promise<boolean> {
    const job = await this.queue.claim(this.workerId, now);
    if (!job) return false;
    try {
      if (job.type === "analyze_submission") await this.analyze(job, now);
      else await this.cleanup(job, now);
      return true;
    } catch (error) {
      if (error instanceof LeaseLostError) return true;
      try {
        await this.requireLease(job);
        await this.handleFailure(job, errorCode(error), now);
      } catch (failureError) {
        if (!(failureError instanceof LeaseLostError)) throw failureError;
      }
      return true;
    }
  }

  private async analyze(job: BackgroundJob, now: Date): Promise<void> {
    const state = await this.processing.begin(job.accountId, job.aggregateId, now);
    if (!state || state.submission.status === "ready" || state.submission.status === "failed") {
      await this.completeLease(job);
      return;
    }
    if (!state.extraction) {
      const image = await this.storage.get(state.submission.imageObjectPath);
      const raw = await this.model.analyze({
        image,
        contentType: state.submission.imageContentType,
        ...(state.submission.supplementalText ? { supplementalText: state.submission.supplementalText } : {}),
      });
      await this.requireLease(job);
      const analysis = validateExtractionOutput(raw, { accountId: job.accountId, submissionId: job.aggregateId }, now);
      await this.processing.save(job.accountId, job.aggregateId, analysis);
    }
    await this.requireLease(job);
    await this.storage.remove(state.submission.imageObjectPath);
    await this.requireLease(job);
    await this.processing.finalize(job.accountId, job.aggregateId, "ready", undefined, now);
    await this.completeLease(job);
  }

  private async cleanup(job: BackgroundJob, now: Date): Promise<void> {
    const state = await this.processing.begin(job.accountId, job.aggregateId, now);
    await this.requireLease(job);
    await this.storage.remove(`${job.accountId}/${job.aggregateId}`);
    await this.requireLease(job);
    if (state && state.submission.status === "processing") {
      await this.processing.finalize(
        job.accountId,
        job.aggregateId,
        state.extraction ? "ready" : "failed",
        state.extraction ? undefined : state.submission.failureCode ?? "analysis_failed",
        now,
      );
    }
    await this.completeLease(job);
  }

  private async handleFailure(job: BackgroundJob, code: string, now: Date): Promise<void> {
    if (job.attempt >= job.maxAttempts) {
      const state = await this.processing.begin(job.accountId, job.aggregateId, now);
      try {
        await this.requireLease(job);
        await this.storage.remove(`${job.accountId}/${job.aggregateId}`);
        await this.requireLease(job);
        if (state && state.submission.status === "processing") {
          await this.processing.finalize(job.accountId, job.aggregateId, state.extraction ? "ready" : "failed", state.extraction ? undefined : code, now);
        }
      } catch {
        await this.processing.enqueueCleanup(job.accountId, job.aggregateId, cleanupJob(job, now));
      }
    }
    const retryAt = new Date(now.getTime() + Math.min(15 * 60_000, 30_000 * 2 ** Math.max(0, job.attempt - 1)));
    await this.queue.fail(job.id, this.workerId, code, retryAt, now);
  }

  private async requireLease(job: BackgroundJob): Promise<void> {
    if (!await this.queue.renew(job.id, this.workerId, new Date())) throw new LeaseLostError();
  }

  private async completeLease(job: BackgroundJob): Promise<void> {
    if (!await this.queue.complete(job.id, this.workerId, new Date())) throw new LeaseLostError();
  }
}

class LeaseLostError extends Error {}

function cleanupJob(source: BackgroundJob, now: Date): BackgroundJob {
  const timestamp = now.toISOString();
  return {
    id: randomUUID(),
    accountId: source.accountId,
    type: "delete_submission_artifacts",
    aggregateId: source.aggregateId,
    idempotencyKey: `cleanup:${source.aggregateId}`,
    status: "queued",
    attempt: 0,
    maxAttempts: 20,
    availableAt: timestamp,
    createdAt: timestamp,
    updatedAt: timestamp,
  };
}

function errorCode(error: unknown): string {
  if (error && typeof error === "object" && "code" in error && typeof error.code === "string") return sanitize(error.code);
  if (error && typeof error === "object" && "name" in error && error.name === "ZodError") return "invalid_model_output";
  return "analysis_failed";
}

function sanitize(value: string): string {
  const normalized = value.toLowerCase().replace(/[^a-z0-9_:-]/g, "_").slice(0, 100);
  return normalized || "analysis_failed";
}
