export interface WorkerRunner {
  runOne(): Promise<boolean>;
}

export interface WorkerLoopOptions {
  pollMs: number;
  errorBackoffMs: number;
  onError?: (error: unknown) => void;
  sleep?: (milliseconds: number, signal: AbortSignal) => Promise<void>;
}

export async function runWorkerLoop(
  worker: WorkerRunner,
  options: WorkerLoopOptions,
  signal: AbortSignal,
): Promise<void> {
  const sleep = options.sleep ?? abortableDelay;
  while (!signal.aborted) {
    let processed: boolean;
    try {
      processed = await worker.runOne();
    } catch (error) {
      options.onError?.(error);
      await sleep(options.errorBackoffMs, signal);
      continue;
    }
    if (!processed) await sleep(options.pollMs, signal);
  }
}

export function abortableDelay(milliseconds: number, signal: AbortSignal): Promise<void> {
  if (signal.aborted) return Promise.resolve();
  return new Promise((resolve) => {
    const timer = setTimeout(finish, milliseconds);
    signal.addEventListener("abort", finish, { once: true });

    function finish(): void {
      clearTimeout(timer);
      signal.removeEventListener("abort", finish);
      resolve();
    }
  });
}
