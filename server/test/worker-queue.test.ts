import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { describe, it } from "node:test";
import { InMemoryStore } from "../src/adapters/in-memory-store.js";
import { InMemoryWorkerQueueRepository } from "../src/adapters/in-memory-worker-queue-repository.js";
import { prepareSubmission } from "../src/domain/submission.js";

const png = Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1]);

describe("Worker queue leases", () => {
  it("claims once, respects retry time and stops at max attempts", async () => {
    const store = new InMemoryStore();
    const queue = new InMemoryWorkerQueueRepository(store);
    const now = new Date("2026-08-31T00:00:00.000Z");
    const prepared = prepareSubmission({ id: randomUUID(), accountId: randomUUID(), image: png, contentType: "image/png", source: "in_app" }, now);
    store.jobs.set(prepared.job.id, { ...prepared.job, maxAttempts: 2 });

    const first = await queue.claim("worker-1", now);
    assert.equal(first?.attempt, 1);
    assert.equal(await queue.claim("worker-2", now), undefined);
    assert.equal(await queue.complete(first!.id, "worker-2", now), false);

    const retryAt = new Date(now.getTime() + 60_000);
    assert.equal(await queue.fail(first!.id, "worker-1", "model_unavailable", retryAt, now), true);
    assert.equal(await queue.claim("worker-2", new Date(now.getTime() + 30_000)), undefined);
    const second = await queue.claim("worker-2", retryAt);
    assert.equal(second?.attempt, 2);
    assert.equal(await queue.fail(second!.id, "worker-2", "model_unavailable", retryAt, retryAt), true);
    assert.equal(store.jobs.get(second!.id)?.status, "dead");
    assert.equal(await queue.claim("worker-3", new Date(retryAt.getTime() + 60_000)), undefined);
  });

  it("reclaims an expired lease and rejects the stale worker completion", async () => {
    const store = new InMemoryStore();
    const queue = new InMemoryWorkerQueueRepository(store);
    const now = new Date("2026-08-31T00:00:00.000Z");
    const prepared = prepareSubmission({ id: randomUUID(), accountId: randomUUID(), image: png, contentType: "image/png", source: "in_app" }, now);
    store.jobs.set(prepared.job.id, prepared.job);

    const first = await queue.claim("worker-1", now);
    const reclaimed = await queue.claim("worker-2", new Date(now.getTime() + 6 * 60_000));
    assert.equal(reclaimed?.id, first?.id);
    assert.equal(reclaimed?.attempt, 2);
    assert.equal(await queue.complete(reclaimed!.id, "worker-1"), false);
    assert.equal(await queue.complete(reclaimed!.id, "worker-2"), true);
    assert.equal(store.jobs.get(reclaimed!.id)?.status, "succeeded");
  });
});
