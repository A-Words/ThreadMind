import type { BackgroundJob } from "../domain/model.ts";
import type { InMemoryStore } from "./in-memory-store.ts";
import type { WorkerQueueRepository } from "./worker-queue-repository.ts";

export class InMemoryWorkerQueueRepository implements WorkerQueueRepository {
  constructor(private readonly store: InMemoryStore) {}

  async claim(workerId: string, now = new Date(), leaseTimeoutMs = 5 * 60_000): Promise<BackgroundJob | undefined> {
    const leaseCutoff = now.getTime() - leaseTimeoutMs;
    for (const [id, job] of this.store.jobs) {
      if (job.status !== "running" || !job.lockedAt || new Date(job.lockedAt).getTime() >= leaseCutoff) continue;
      const unlocked = withoutLock(job);
      this.store.jobs.set(id, job.attempt >= job.maxAttempts
        ? { ...unlocked, status: "dead", lastErrorCode: "lease_expired", updatedAt: now.toISOString() }
        : { ...unlocked, status: "queued", lastErrorCode: "lease_expired", availableAt: now.toISOString(), updatedAt: now.toISOString() });
    }
    const candidate = [...this.store.jobs.values()]
      .filter((job) => ["queued", "failed"].includes(job.status) && job.attempt < job.maxAttempts && new Date(job.availableAt) <= now)
      .sort((left, right) => left.availableAt.localeCompare(right.availableAt) || left.createdAt.localeCompare(right.createdAt) || left.id.localeCompare(right.id))[0];
    if (!candidate) return undefined;
    const { lastErrorCode: _lastErrorCode, ...claimable } = candidate;
    const claimed: BackgroundJob = {
      ...claimable,
      status: "running",
      attempt: candidate.attempt + 1,
      lockedAt: now.toISOString(),
      lockedBy: workerId,
      updatedAt: now.toISOString(),
    };
    this.store.jobs.set(claimed.id, claimed);
    return structuredClone(claimed);
  }

  async complete(jobId: string, workerId: string, now = new Date()): Promise<boolean> {
    const job = this.store.jobs.get(jobId);
    if (!job || job.status !== "running" || job.lockedBy !== workerId) return false;
    this.store.jobs.set(jobId, { ...withoutLock(job), status: "succeeded", updatedAt: now.toISOString() });
    return true;
  }

  async renew(jobId: string, workerId: string, now = new Date()): Promise<boolean> {
    const job = this.store.jobs.get(jobId);
    if (!job || job.status !== "running" || job.lockedBy !== workerId) return false;
    this.store.jobs.set(jobId, { ...job, lockedAt: now.toISOString(), updatedAt: now.toISOString() });
    return true;
  }

  async fail(jobId: string, workerId: string, errorCode: string, retryAt: Date, now = new Date()): Promise<boolean> {
    const job = this.store.jobs.get(jobId);
    if (!job || job.status !== "running" || job.lockedBy !== workerId) return false;
    this.store.jobs.set(jobId, {
      ...withoutLock(job),
      status: job.attempt >= job.maxAttempts ? "dead" : "failed",
      lastErrorCode: errorCode,
      availableAt: retryAt.toISOString(),
      updatedAt: now.toISOString(),
    });
    return true;
  }
}

function withoutLock(job: BackgroundJob): BackgroundJob {
  const { lockedAt: _lockedAt, lockedBy: _lockedBy, ...unlocked } = job;
  return unlocked;
}
