import type { Insertable, Kysely, Selectable } from "kysely";
import { DomainError } from "../domain/errors.ts";
import type { BackgroundJob, ScreenshotSubmission } from "../domain/model.ts";
import { sameSubmissionContent } from "../domain/submission.ts";
import { retryTransient, withAccount } from "../database/account-transaction.ts";
import type { BackgroundJobsTable, ScreenshotSubmissionsTable, ThreadMindDatabase } from "../database/schema.ts";
import type { SubmissionRepository } from "./submission-repository.ts";

export class KyselySubmissionRepository implements SubmissionRepository {
  constructor(private readonly database: Kysely<ThreadMindDatabase>) {}

  async find(accountId: string, id: string): Promise<ScreenshotSubmission | undefined> {
    return retryTransient(() => withAccount(this.database, accountId, async (transaction) => {
      const row = await transaction
        .selectFrom("threadmind.screenshot_submissions")
        .selectAll()
        .where("id", "=", id)
        .where("status", "!=", "deleted")
        .executeTakeFirst();
      return row ? toSubmission(row) : undefined;
    }));
  }

  async createWithJob(submission: ScreenshotSubmission, job: BackgroundJob): Promise<ScreenshotSubmission> {
    return retryTransient(() => withAccount(this.database, submission.accountId, async (transaction) => {
      const existingRow = await transaction
        .selectFrom("threadmind.screenshot_submissions")
        .selectAll()
        .where("id", "=", submission.id)
        .executeTakeFirst();
      if (existingRow) {
        const existing = toSubmission(existingRow);
        if (!sameSubmissionContent(existing, submission)) throw new DomainError("submission_conflict", "Submission id already has different content");
        return existing;
      }
      const created = await transaction
        .insertInto("threadmind.screenshot_submissions")
        .values(toSubmissionRow(submission))
        .returningAll()
        .executeTakeFirstOrThrow();
      await transaction
        .insertInto("threadmind.background_jobs")
        .values(toJobRow(job))
        .onConflict((conflict) => conflict.columns(["account_id", "job_type", "idempotency_key"]).doNothing())
        .execute();
      return toSubmission(created);
    }));
  }

  async remove(accountId: string, id: string): Promise<boolean> {
    return retryTransient(() => withAccount(this.database, accountId, async (transaction) => {
      const deleted = await transaction
        .deleteFrom("threadmind.screenshot_submissions")
        .where("id", "=", id)
        .returning("id")
        .executeTakeFirst();
      return deleted !== undefined;
    }));
  }
}

function toSubmissionRow(submission: ScreenshotSubmission): Insertable<ScreenshotSubmissionsTable> {
  return {
    id: submission.id,
    account_id: submission.accountId,
    image_object_path: submission.imageObjectPath,
    image_content_type: submission.imageContentType,
    image_byte_size: submission.imageByteSize,
    image_sha256: submission.imageSha256,
    supplemental_text: submission.supplementalText ?? null,
    submission_source: submission.source,
    status: submission.status,
    failure_code: submission.failureCode ?? null,
    processing_started_at: submission.processingStartedAt ?? null,
    completed_at: submission.completedAt ?? null,
    created_at: submission.createdAt,
    updated_at: submission.updatedAt,
  };
}

function toJobRow(job: BackgroundJob): Insertable<BackgroundJobsTable> {
  return {
    id: job.id,
    account_id: job.accountId,
    job_type: job.type,
    aggregate_id: job.aggregateId,
    idempotency_key: job.idempotencyKey,
    status: job.status,
    attempt: job.attempt,
    max_attempts: job.maxAttempts,
    available_at: job.availableAt,
    locked_at: job.lockedAt ?? null,
    locked_by: job.lockedBy ?? null,
    last_error_code: job.lastErrorCode ?? null,
    created_at: job.createdAt,
    updated_at: job.updatedAt,
  };
}

function toSubmission(row: Selectable<ScreenshotSubmissionsTable>): ScreenshotSubmission {
  return {
    id: row.id,
    accountId: row.account_id,
    imageObjectPath: row.image_object_path,
    imageContentType: row.image_content_type,
    imageByteSize: Number(row.image_byte_size),
    imageSha256: row.image_sha256,
    ...(row.supplemental_text ? { supplementalText: row.supplemental_text } : {}),
    source: row.submission_source,
    status: row.status,
    ...(row.failure_code ? { failureCode: row.failure_code } : {}),
    ...(row.processing_started_at ? { processingStartedAt: new Date(row.processing_started_at).toISOString() } : {}),
    ...(row.completed_at ? { completedAt: new Date(row.completed_at).toISOString() } : {}),
    createdAt: new Date(row.created_at).toISOString(),
    updatedAt: new Date(row.updated_at).toISOString(),
  };
}
