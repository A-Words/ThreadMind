import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { describe, it } from "node:test";
import { InMemoryStore } from "../src/adapters/in-memory-store.js";
import { InMemorySubmissionRepository } from "../src/adapters/in-memory-submission-repository.js";
import { InMemorySubmissionProcessingRepository } from "../src/adapters/in-memory-submission-processing-repository.js";
import { InMemoryTemporaryImageStorage } from "../src/adapters/temporary-image-storage.js";
import { InMemoryWorkerQueueRepository } from "../src/adapters/in-memory-worker-queue-repository.js";
import { prepareSubmission } from "../src/domain/submission.js";
import type { VisionExtractionModel } from "../src/extraction/vision-extraction-model.js";
import { SubmissionWorker } from "../src/worker/submission-worker.js";

const png = Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1]);

describe("Submission worker", () => {
  it("persists evidence-backed candidates, removes the image and only then becomes ready", async () => {
    const harness = await createHarness(validOutput());
    assert.equal(await harness.worker.runOne(harness.now), true);
    const submission = harness.store.submissions.get(harness.submissionId);
    assert.equal(submission?.status, "ready");
    assert.equal(harness.storage.has(`${harness.accountId}/${harness.submissionId}`), false);
    assert.equal(harness.store.extractions.get(harness.submissionId)?.evidenceSpans[0]?.id, "e1");
    assert.equal([...harness.store.cards.values()][0]?.status, "ready");
    assert.equal([...harness.store.memories.values()][0]?.epistemicStatus, "fact");
    assert.equal([...harness.store.jobs.values()][0]?.status, "succeeded");
    assert.equal(harness.model.calls, 1);
  });

  it("retries only cleanup after analysis was persisted", async () => {
    const harness = await createHarness(validOutput());
    harness.storage.failRemovals = 1;
    await harness.worker.runOne(harness.now);
    assert.equal(harness.store.submissions.get(harness.submissionId)?.status, "processing");
    assert.equal(harness.model.calls, 1);
    await harness.worker.runOne(new Date(harness.now.getTime() + 31_000));
    assert.equal(harness.store.submissions.get(harness.submissionId)?.status, "ready");
    assert.equal(harness.model.calls, 1);
  });

  it("deletes the image before marking terminal model failure", async () => {
    const harness = await createHarness({ messages: [] });
    const job = [...harness.store.jobs.values()][0]!;
    harness.store.jobs.set(job.id, { ...job, maxAttempts: 1 });
    await harness.worker.runOne(harness.now);
    assert.equal(harness.storage.has(`${harness.accountId}/${harness.submissionId}`), false);
    assert.equal(harness.store.submissions.get(harness.submissionId)?.status, "failed");
    assert.equal(harness.store.jobs.get(job.id)?.status, "dead");
  });

  it("enqueues a cleanup job when terminal image deletion fails", async () => {
    const harness = await createHarness({ messages: [] });
    const analyzeJob = [...harness.store.jobs.values()][0]!;
    harness.store.jobs.set(analyzeJob.id, { ...analyzeJob, maxAttempts: 1 });
    harness.storage.failRemovals = 1;
    await harness.worker.runOne(harness.now);
    assert.equal(harness.store.submissions.get(harness.submissionId)?.status, "processing");
    assert.equal([...harness.store.jobs.values()].some((job) => job.type === "delete_submission_artifacts" && job.status === "queued"), true);
    await harness.worker.runOne(new Date(harness.now.getTime() + 1));
    assert.equal(harness.store.submissions.get(harness.submissionId)?.status, "failed");
    assert.equal(harness.storage.has(`${harness.accountId}/${harness.submissionId}`), false);
  });

  it("does not persist or delete after another worker takes the lease during analysis", async () => {
    const harness = await createHarness(validOutput());
    const job = [...harness.store.jobs.values()][0]!;
    harness.model.afterAnalyze = () => {
      const claimed = harness.store.jobs.get(job.id)!;
      harness.store.jobs.set(job.id, { ...claimed, lockedBy: "worker-2" });
    };
    await harness.worker.runOne(harness.now);
    assert.equal(harness.store.submissions.get(harness.submissionId)?.status, "processing");
    assert.equal(harness.store.extractions.has(harness.submissionId), false);
    assert.equal(harness.store.cards.size, 0);
    assert.equal(harness.storage.has(`${harness.accountId}/${harness.submissionId}`), true);
    assert.equal(harness.store.jobs.get(job.id)?.lockedBy, "worker-2");
  });

  it("does not finalize a failure after another worker takes the lease", async () => {
    const harness = await createHarness(validOutput());
    const job = [...harness.store.jobs.values()][0]!;
    harness.model.error = new Error("provider_unavailable");
    harness.model.afterAnalyze = () => {
      const claimed = harness.store.jobs.get(job.id)!;
      harness.store.jobs.set(job.id, { ...claimed, lockedBy: "worker-2" });
    };
    await harness.worker.runOne(harness.now);
    assert.equal(harness.store.submissions.get(harness.submissionId)?.status, "processing");
    assert.equal(harness.storage.has(`${harness.accountId}/${harness.submissionId}`), true);
    assert.equal(harness.store.jobs.get(job.id)?.lockedBy, "worker-2");
    assert.equal(harness.store.jobs.get(job.id)?.lastErrorCode, undefined);
  });
});

class FakeModel implements VisionExtractionModel {
  calls = 0;
  afterAnalyze?: () => void;
  error?: Error;
  constructor(private readonly output: unknown) {}
  async analyze(): Promise<unknown> {
    this.calls += 1;
    this.afterAnalyze?.();
    if (this.error) throw this.error;
    return structuredClone(this.output);
  }
}

class FlakyStorage extends InMemoryTemporaryImageStorage {
  failRemovals = 0;
  override async remove(path: string): Promise<void> {
    if (this.failRemovals > 0) { this.failRemovals -= 1; throw new Error("storage_unavailable"); }
    return super.remove(path);
  }
}

async function createHarness(output: unknown) {
  const store = new InMemoryStore();
  const submissions = new InMemorySubmissionRepository(store);
  const processing = new InMemorySubmissionProcessingRepository(store);
  const queue = new InMemoryWorkerQueueRepository(store);
  const storage = new FlakyStorage();
  const model = new FakeModel(output);
  const accountId = randomUUID();
  const submissionId = randomUUID();
  const now = new Date("2026-08-31T00:00:00.000Z");
  const prepared = prepareSubmission({ id: submissionId, accountId, image: png, contentType: "image/png", source: "in_app", supplementalText: "客户陈先生" }, now);
  await storage.putIfAbsent(prepared.submission.imageObjectPath, png, "image/png", prepared.submission.imageSha256);
  await submissions.createWithJob(prepared.submission, prepared.job);
  const worker = new SubmissionWorker("worker-1", queue, processing, storage, model);
  return { store, storage, model, worker, accountId, submissionId, now };
}

function validOutput() {
  return {
    messages: [{ id: "m1", order: 0, text: "陈先生 chen@example.com", speaker: "对方", confidence: 0.99 }],
    participants: [{ id: "p1", displayName: "陈先生", evidenceRefs: ["e1"], confidence: 0.95 }],
    entities: [{ id: "entity1", type: "email", value: "chen@example.com", evidenceRefs: ["e1"], confidence: 0.99 }],
    temporalExpressions: [],
    actionCandidates: [{
      id: "action1",
      type: "create_contact",
      fields: { displayName: "陈先生", contactMethod: "chen@example.com", targetContactAccountId: "local" },
      evidenceRefs: ["e1"],
      fieldConfidence: { displayName: 0.95, contactMethod: 0.99 },
      validationIssues: [],
      targetAccountId: "local",
    }],
    memoryCandidates: [{
      id: "memory1",
      subjectRefs: ["p1"],
      type: "profile",
      assertion: "陈先生的邮箱是 chen@example.com",
      epistemicStatus: "fact",
      confidence: 0.99,
      sensitivity: "normal",
      evidenceRefs: ["e1"],
    }],
    evidenceSpans: [{ id: "e1", messageId: "m1", excerpt: "陈先生 chen@example.com", confidence: 0.99 }],
    warnings: [],
    modelTrace: { model: "fake-vision", promptVersion: "v1", durationMs: 10 },
  };
}
