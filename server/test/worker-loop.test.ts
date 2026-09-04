import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { readWorkerConfig, workerDatabaseEnv } from "../src/worker/config.js";
import { safeWorkerErrorCode } from "../src/worker/error-code.js";
import { runWorkerLoop } from "../src/worker/worker-loop.js";

describe("Worker process loop", () => {
  it("continues immediately after work and waits after an empty poll", async () => {
    const controller = new AbortController();
    const calls: string[] = [];
    const results = [true, false];
    await runWorkerLoop({
      async runOne() {
        calls.push("run");
        return results.shift() ?? false;
      },
    }, {
      pollMs: 200,
      errorBackoffMs: 500,
      async sleep(milliseconds) {
        calls.push(`sleep:${milliseconds}`);
        controller.abort();
      },
    }, controller.signal);
    assert.deepEqual(calls, ["run", "run", "sleep:200"]);
  });

  it("reports unexpected loop errors, backs off, and can stop", async () => {
    const controller = new AbortController();
    const errors: unknown[] = [];
    let calls = 0;
    await runWorkerLoop({
      async runOne() {
        calls += 1;
        throw new Error("database unavailable");
      },
    }, {
      pollMs: 200,
      errorBackoffMs: 500,
      onError: (error) => errors.push(error),
      async sleep(milliseconds) {
        assert.equal(milliseconds, 500);
        controller.abort();
      },
    }, controller.signal);
    assert.equal(calls, 1);
    assert.equal(errors.length, 1);
  });

  it("validates bounded polling configuration", () => {
    assert.deepEqual(readWorkerConfig({
      THREADMIND_WORKER_ID: "worker-test",
      THREADMIND_WORKER_POLL_MS: "250",
      THREADMIND_WORKER_ERROR_BACKOFF_MS: "750",
    }), { workerId: "worker-test", pollMs: 250, errorBackoffMs: 750 });
    assert.throws(() => readWorkerConfig({ THREADMIND_WORKER_POLL_MS: "60001" }), /between 100 and 60000/);
  });

  it("uses the dedicated least-privilege Worker database connection", () => {
    const env = workerDatabaseEnv({
      DATABASE_URL: "postgresql://api-role",
      THREADMIND_WORKER_DATABASE_URL: "postgresql://worker-role",
      THREADMIND_WORKER_DATABASE_POOL_MAX: "3",
    });
    assert.equal(env.DATABASE_URL, "postgresql://worker-role");
    assert.equal(env.DATABASE_POOL_MAX, "3");
    assert.throws(() => workerDatabaseEnv({ DATABASE_URL: "postgresql://api-role" }), /THREADMIND_WORKER_DATABASE_URL is required/);
  });

  it("reports safe connection timeout codes through nested transport errors", () => {
    assert.equal(safeWorkerErrorCode(new Error("Connection terminated due to connection timeout")), "connection_timeout");
    assert.equal(safeWorkerErrorCode(new TypeError("fetch failed", { cause: Object.assign(new Error("private"), { code: "ETIMEDOUT" }) })), "etimedout");
    assert.equal(safeWorkerErrorCode(new Error("private database details")), "worker_loop_failed");
  });
});
