export function safeWorkerErrorCode(error: unknown): string {
  let current = error;
  for (let depth = 0; depth < 4 && current && typeof current === "object"; depth += 1) {
    if ("code" in current && typeof current.code === "string" && current.code.trim()) return sanitize(current.code);
    if ("message" in current && typeof current.message === "string"
      && /connection (?:terminated )?(?:due to )?(?:a )?connection timeout|connection timeout expired/i.test(current.message)) {
      return "connection_timeout";
    }
    current = "cause" in current ? current.cause : undefined;
  }
  return "worker_loop_failed";
}

function sanitize(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9_:-]/g, "_").slice(0, 100) || "worker_loop_failed";
}
