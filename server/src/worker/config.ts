import { hostname } from "node:os";

export interface WorkerConfig {
  workerId: string;
  pollMs: number;
  errorBackoffMs: number;
}

export function readWorkerConfig(env: NodeJS.ProcessEnv): WorkerConfig {
  return {
    workerId: env.THREADMIND_WORKER_ID?.trim() || `worker:${hostname()}:${process.pid}`,
    pollMs: integerSetting(env, "THREADMIND_WORKER_POLL_MS", 2_000, 100, 60_000),
    errorBackoffMs: integerSetting(env, "THREADMIND_WORKER_ERROR_BACKOFF_MS", 5_000, 100, 60_000),
  };
}

export function workerDatabaseEnv(env: NodeJS.ProcessEnv): NodeJS.ProcessEnv {
  const connectionString = env.THREADMIND_WORKER_DATABASE_URL?.trim();
  if (!connectionString) throw new Error("THREADMIND_WORKER_DATABASE_URL is required");
  return {
    ...env,
    DATABASE_URL: connectionString,
    ...(env.THREADMIND_WORKER_DATABASE_POOL_MAX
      ? { DATABASE_POOL_MAX: env.THREADMIND_WORKER_DATABASE_POOL_MAX }
      : {}),
  };
}

function integerSetting(env: NodeJS.ProcessEnv, name: string, fallback: number, min: number, max: number): number {
  const value = Number(env[name] ?? fallback);
  if (!Number.isInteger(value) || value < min || value > max) throw new Error(`${name} must be an integer between ${min} and ${max}`);
  return value;
}
