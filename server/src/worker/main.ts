import { KyselySubmissionProcessingRepository } from "../adapters/kysely-submission-processing-repository.ts";
import { KyselyWorkerQueueRepository } from "../adapters/kysely-worker-queue-repository.ts";
import { createTemporaryImageStorage } from "../adapters/supabase-temporary-image-storage.ts";
import { createDatabase } from "../database/database.ts";
import { createVisionExtractionModel } from "../extraction/openai-responses-vision-model.ts";
import { readWorkerConfig, workerDatabaseEnv } from "./config.ts";
import { SubmissionWorker } from "./submission-worker.ts";
import { runWorkerLoop } from "./worker-loop.ts";

requireWorkerEnvironment(process.env);
const config = readWorkerConfig(process.env);
const database = createDatabase(workerDatabaseEnv(process.env), "threadmind-worker");
const worker = new SubmissionWorker(
  config.workerId,
  new KyselyWorkerQueueRepository(database),
  new KyselySubmissionProcessingRepository(database),
  createTemporaryImageStorage(process.env),
  createVisionExtractionModel(process.env),
);
const shutdown = new AbortController();
const stop = () => shutdown.abort();
process.once("SIGINT", stop);
process.once("SIGTERM", stop);

console.info("threadmind_worker_started", { workerId: config.workerId });
try {
  await runWorkerLoop(worker, {
    pollMs: config.pollMs,
    errorBackoffMs: config.errorBackoffMs,
    onError: (error) => console.error("threadmind_worker_loop_error", { code: safeErrorCode(error) }),
  }, shutdown.signal);
} finally {
  process.removeListener("SIGINT", stop);
  process.removeListener("SIGTERM", stop);
  await database.destroy();
  console.info("threadmind_worker_stopped", { workerId: config.workerId });
}

function requireWorkerEnvironment(env: NodeJS.ProcessEnv): void {
  for (const name of ["THREADMIND_WORKER_DATABASE_URL", "SUPABASE_URL", "SUPABASE_SECRET_KEY", "OPENAI_API_KEY", "THREADMIND_VISION_MODEL"]) {
    if (!env[name]?.trim()) throw new Error(`${name} is required`);
  }
}

function safeErrorCode(error: unknown): string {
  if (error && typeof error === "object" && "code" in error && typeof error.code === "string") {
    return sanitize(error.code);
  }
  return "worker_loop_failed";
}

function sanitize(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9_:-]/g, "_").slice(0, 100) || "worker_loop_failed";
}
