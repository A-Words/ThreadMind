import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { it } from "node:test";
import { InMemoryStore } from "../src/adapters/in-memory-store.ts";
import { InMemoryMemoryRepository } from "../src/adapters/in-memory-memory-repository.ts";
import { InMemoryInsightRepository } from "../src/adapters/in-memory-insight-repository.ts";
import { InMemorySubmissionRepository } from "../src/adapters/in-memory-submission-repository.ts";
import { createMemory } from "../src/domain/memory.ts";
import type { ActionCard, ActionReceipt, ContextExtraction } from "../src/domain/model.ts";
import { InsightService } from "../src/insight/insight-service.ts";
import { EvidenceBackedInsightGenerator, type InsightGenerationInput } from "../src/insight/insight-generator.ts";

it("recalls old related evidence before limiting and excludes inactive, sourceless and other-account memory", async () => {
  const store = new InMemoryStore();
  const repository = new InMemoryMemoryRepository(store);
  const old = memory("Chen", "Chen prefers email", "old:e1");
  await repository.create(old);
  for (let i = 0; i < 105; i++) await repository.create({ ...memory("Other", "Unrelated", `new:${i}`), updatedAt: "2026-09-01T00:00:00Z" });
  const current = await repository.create(memory("Unknown", "Current context", "submission-1:e1"));
  const other = { ...old, id: randomUUID(), accountId: "a2" };
  const sourceless = { ...old, id: randomUUID(), sourceEvidence: [] };
  store.memories.set(other.id, other);
  store.memories.set(sourceless.id, sourceless);
  const query = { subjectRefs: ["Chen"], submissionId: "submission-1" };
  assert.deepEqual(new Set((await repository.recallActive("a1", query)).map((m) => m.id)), new Set([old.id, current.id]));
  const revised = await repository.revise("a1", old.id, "Chen now prefers phone", "user:correction");
  assert.deepEqual(new Set((await repository.recallActive("a1", query)).map((m) => m.id)), new Set([revised!.id, current.id]));
  await repository.remove("a1", revised!.id);
  await repository.create(old); // receipt replay must never resurrect a deleted memory
  assert.deepEqual((await repository.recallActive("a1", query)).map((m) => m.id), [current.id]);
});

it("bounds recall to 30 recent matching records with deterministic ordering", async () => {
  const repository = new InMemoryMemoryRepository(new InMemoryStore());
  const records = Array.from({ length: 35 }, () => memory("Chen", "Related", "history:e1"));
  for (const record of records) await repository.create(record);
  const results = await repository.recallActive("a1", { subjectRefs: ["Chen"], submissionId: "submission-1" });
  assert.deepEqual(results.map((m) => m.id), records.map((m) => m.id).sort().reverse().slice(0, 30));
  assert.deepEqual(await repository.recallActive("a1", { subjectRefs: [], submissionId: "absent" }), []);
});

it("assembles current extraction and person memory, preserves replay, and reflects corrections on later receipts", async () => {
  const store = new InMemoryStore();
  const memories = new InMemoryMemoryRepository(store);
  const captured: InsightGenerationInput[] = [];
  const generator = new EvidenceBackedInsightGenerator();
  const service = new InsightService(new InMemoryInsightRepository(store), memories, {
    generate: async (input) => { captured.push(input); return generator.generate(input); },
  }, new InMemorySubmissionRepository(store));
  const extraction: ContextExtraction = {
    id: "extraction-1", accountId: "a1", submissionId: "submission-1",
    messages: [{ id: "m1", order: 0, text: "Chen will send the proposal", confidence: 1 }],
    participants: [{ id: "p1", displayName: "Chen", evidenceRefs: ["e1"], confidence: 1 }],
    evidenceSpans: [{ id: "e1", messageId: "m1", excerpt: "Chen will send the proposal", confidence: 1 }],
    entities: [], temporalExpressions: [], actionCandidates: [], warnings: [],
    modelTrace: { model: "test", promptVersion: "v1" }, createdAt: "2026-09-01T00:00:00Z",
  };
  store.extractions.set("submission-1", extraction);
  const related = await memories.create(memory("Chen", "Chen prefers email", "history:e1"));
  await memories.create(memory("Coffee", "A title is not a person", "history:e2"));
  const card: ActionCard = {
    id: "card-1", accountId: "a1", submissionId: "submission-1", version: 1, type: "create_meeting",
    fields: { title: "Coffee", startsAt: "2026-09-05T10:00:00Z", attendees: "alice@example.com" },
    status: "succeeded", blockers: [], validationIssues: [], fieldConfidence: {},
    evidence: [{ sourceId: "submission-1", excerpt: "Chen will send the proposal", confidence: 1 }],
  };
  const receipt: ActionReceipt = {
    id: "receipt-1", accountId: "a1", actionCardId: "card-1", confirmedVersion: 1, attempt: 1,
    status: "succeeded", provider: "android_calendar", targetRecordId: "calendar-1",
    startedAt: "2026-09-01T00:00:00Z", completedAt: "2026-09-01T00:00:01Z",
  };
  const first = await service.ensureForReceipt(card, receipt);
  assert.deepEqual(captured[0]!.extraction, extraction);
  assert.equal(captured[0]!.memories.some((m) => m.id === related.id), true);
  assert.equal(captured[0]!.memories.some((m) => m.subjectRefs.includes("Coffee")), false);
  const actionMemory = captured[0]!.memories.find((m) => m.sourceRefs.some((ref) => ref.includes(":receipt:")))!;
  await memories.remove("a1", actionMemory.id);
  assert.equal((await service.ensureForReceipt(card, receipt))!.id, first!.id);
  assert.equal(captured.length, 1);
  assert.equal(store.memories.get(actionMemory.id)!.status, "deleted");
  await memories.revise("a1", related.id, "Chen now prefers phone", "user:correction");
  await service.ensureForReceipt({ ...card, id: "card-2" }, { ...receipt, id: "receipt-2", actionCardId: "card-2" });
  assert.equal(captured[1]!.memories.some((m) => m.assertion === "Chen now prefers phone"), true);
  assert.equal(captured[1]!.memories.some((m) => m.id === related.id), false);
  await assert.rejects(service.ensureForReceipt(card, { ...receipt, accountId: "a2" }), /successful action/);
});

function memory(subject: string, assertion: string, source: string) {
  return createMemory({
    accountId: "a1", subjectRefs: [subject], type: "preference", assertion,
    epistemicStatus: "fact", confidence: 1, sensitivity: "normal", sourceRefs: [source],
    sourceEvidence: [{ sourceId: source, excerpt: assertion, confidence: 1 }],
  }, new Date("2026-01-01T00:00:00Z"));
}
