import type { Kysely, Selectable } from "kysely";
import type { BackgroundJob } from "../domain/model.ts";
import { withWorker } from "../database/account-transaction.ts";
import type { BackgroundJobsTable, ThreadMindDatabase } from "../database/schema.ts";
import type { WorkerQueueRepository } from "./worker-queue-repository.ts";

export class KyselyWorkerQueueRepository implements WorkerQueueRepository {
  constructor(private readonly database: Kysely<ThreadMindDatabase>) {}

  async claim(workerId: string, now = new Date(), leaseTimeoutMs = 5 * 60_000): Promise<BackgroundJob | undefined> {
    return withWorker(this.database, async (transaction) => {
      const leaseCutoff = new Date(now.getTime() - leaseTimeoutMs);
      const expired = await transaction
        .selectFrom("threadmind.background_jobs")
        .selectAll()
        .where("status", "=", "running")
        .where("locked_at", "<", leaseCutoff)
        .orderBy("locked_at")
        .limit(100)
        .forUpdate()
        .skipLocked()
        .execute();
      for (const job of expired) {
        await transaction
          .updateTable("threadmind.background_jobs")
          .set({
            status: job.attempt >= job.max_attempts ? "dead" : "queued",
            locked_at: null,
            locked_by: null,
            last_error_code: "lease_expired",
            available_at: now,
            updated_at: now,
          })
          .where("id", "=", job.id)
          .execute();
      }
      const candidate = await transaction
        .selectFrom("threadmind.background_jobs")
        .selectAll()
        .where("status", "in", ["queued", "failed"])
        .where("available_at", "<=", now)
        .whereRef("attempt", "<", "max_attempts")
        .orderBy("available_at")
        .orderBy("created_at")
        .orderBy("id")
        .limit(1)
        .forUpdate()
        .skipLocked()
        .executeTakeFirst();
      if (!candidate) return undefined;
      const claimed = await transaction
        .updateTable("threadmind.background_jobs")
        .set({
          status: "running",
          attempt: candidate.attempt + 1,
          locked_at: now,
          locked_by: workerId,
          last_error_code: null,
          updated_at: now,
        })
        .where("id", "=", candidate.id)
        .returningAll()
        .executeTakeFirstOrThrow();
      return toJob(claimed);
    });
  }

  async complete(jobId: string, workerId: string, now = new Date()): Promise<boolean> {
    return withWorker(this.database, async (transaction) => {
      const result = await transaction
        .updateTable("threadmind.background_jobs")
        .set({ status: "succeeded", locked_at: null, locked_by: null, updated_at: now })
        .where("id", "=", jobId)
        .where("status", "=", "running")
        .where("locked_by", "=", workerId)
        .returning("id")
        .executeTakeFirst();
      return result !== undefined;
    });
  }

  async renew(jobId: string, workerId: string, now = new Date()): Promise<boolean> {
    return withWorker(this.database, async (transaction) => {
      const result = await transaction
        .updateTable("threadmind.background_jobs")
        .set({ locked_at: now, updated_at: now })
        .where("id", "=", jobId)
        .where("status", "=", "running")
        .where("locked_by", "=", workerId)
        .returning("id")
        .executeTakeFirst();
      return result !== undefined;
    });
  }

  async fail(jobId: string, workerId: string, errorCode: string, retryAt: Date, now = new Date()): Promise<boolean> {
    return withWorker(this.database, async (transaction) => {
      const job = await transaction
        .selectFrom("threadmind.background_jobs")
        .select(["id", "attempt", "max_attempts"])
        .where("id", "=", jobId)
        .where("status", "=", "running")
        .where("locked_by", "=", workerId)
        .forUpdate()
        .executeTakeFirst();
      if (!job) return false;
      await transaction
        .updateTable("threadmind.background_jobs")
        .set({
          status: job.attempt >= job.max_attempts ? "dead" : "failed",
          locked_at: null,
          locked_by: null,
          last_error_code: errorCode,
          available_at: retryAt,
          updated_at: now,
        })
        .where("id", "=", jobId)
        .execute();
      return true;
    });
  }
}

function toJob(row: Selectable<BackgroundJobsTable>): BackgroundJob {
  return {
    id: row.id,
    accountId: row.account_id,
    type: row.job_type,
    aggregateId: row.aggregate_id,
    idempotencyKey: row.idempotency_key,
    status: row.status,
    attempt: row.attempt,
    maxAttempts: row.max_attempts,
    availableAt: new Date(row.available_at).toISOString(),
    ...(row.locked_at ? { lockedAt: new Date(row.locked_at).toISOString() } : {}),
    ...(row.locked_by ? { lockedBy: row.locked_by } : {}),
    ...(row.last_error_code ? { lastErrorCode: row.last_error_code } : {}),
    createdAt: new Date(row.created_at).toISOString(),
    updatedAt: new Date(row.updated_at).toISOString(),
  };
}
