import type { BackgroundJob } from "../domain/model.ts";

export interface WorkerQueueRepository {
  claim(workerId: string, now?: Date, leaseTimeoutMs?: number): Promise<BackgroundJob | undefined>;
  complete(jobId: string, workerId: string, now?: Date): Promise<boolean>;
  fail(jobId: string, workerId: string, errorCode: string, retryAt: Date, now?: Date): Promise<boolean>;
}
