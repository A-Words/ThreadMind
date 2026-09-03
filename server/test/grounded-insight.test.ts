import assert from "node:assert/strict";
import { it } from "node:test";
import { createMemory, reviseMemory } from "../src/domain/memory.ts";
import { GroundedInsightGenerator, assembleInsightContext } from "../src/insight/grounded-insight-generator.ts";
import type { InsightGenerationInput } from "../src/insight/insight-generator.ts";

it("synthesizes multiple memories with current context and restores evidence from trusted premises", async () => {
  const input = fixture();
  let calls = 0;
  const generator = new GroundedInsightGenerator({
    model: "test-model", promptVersion: "test-v1", synthesize: async (context) => {
      calls++;
      assert.equal(context.contactContext, "device_contacts_not_available");
      assert.equal(context.currentContext!.messages[0]!.text, "Please send the proposal before our meeting");
      const memories = context.premises.filter((p) => p.kind === "memory");
      assert.equal(memories.length, 2);
      assert.equal(memories[0]!.assertion, "Chen prefers email");
      return { items: [item(memories.map((p) => p.key))] };
    },
  });
  const output = await generator.generate(input);
  assert.equal(calls, 1);
  assert.deepEqual(output.items[0]!.evidenceRefs, ["history:email", "history:proposal"]);
  assert.equal(output.items[0]!.evidence[0]!.excerpt, "Chen prefers email");
  assert.equal(output.modelTrace.model, "test-model");
});

it("rejects fabricated citations, promoted inferences, excessive confidence, and advice marked as fact", async () => {
  const input = fixture();
  input.memories[0]!.epistemicStatus = "inference";
  input.memories[0]!.confidence = 0.6;
  const key = assembleInsightContext(input).premises.find((p) => p.kind === "memory")!.key;
  for (const bad of [
    item(["invented"]),
    { ...item([key]), kind: "relationship_context", epistemicStatus: "fact", confidence: 0.5, suggestedAction: null },
    { ...item([key]), confidence: 0.9 },
    { ...item(["e1"]), epistemicStatus: "fact" },
    { ...item([key]), evidence: [{ sourceId: "fake", excerpt: "invented", confidence: 1 }] },
  ]) {
    await assert.rejects(new GroundedInsightGenerator({ model: "test", promptVersion: "v1", synthesize: async () => ({ items: [bad] }) }).generate(input));
  }
});

it("uses corrected assertion and correction evidence, excludes deleted/cross-account memory and detects foreign context", () => {
  const input = fixture();
  const [, corrected] = reviseMemory(input.memories[0]!, "Chen now prefers phone", "user:correction");
  input.memories = [corrected, { ...input.memories[1]!, status: "deleted" }, { ...input.memories[1]!, accountId: "a2" }];
  const context = assembleInsightContext(input);
  const memories = context.premises.filter((p) => p.kind === "memory");
  assert.equal(memories.length, 1);
  assert.equal(memories[0]!.assertion, "Chen now prefers phone");
  assert.deepEqual(memories[0]!.evidence.map((e) => e.excerpt), ["Chen now prefers phone"]);
  input.extraction!.accountId = "a2";
  assert.throws(() => assembleInsightContext(input), /context_mismatch/);
});

function item(evidenceKeys: string[]) {
  return {
    kind: "next_step", title: "会前邮件发送方案", explanation: "对方偏好邮件，并约定会前看方案，可先发方案供其准备。",
    epistemicStatus: "inference", confidence: 0.5, evidenceKeys,
    suggestedAction: "给 Chen 发邮件附上方案", suggestedAt: null,
  };
}

function fixture(): InsightGenerationInput {
  const evidence = { sourceId: "submission-1", excerpt: "Please send the proposal before our meeting", confidence: 1 };
  return {
    card: { id: "card-1", accountId: "a1", submissionId: "submission-1", type: "create_meeting", version: 1,
      fields: { title: "Proposal review", attendees: ["Chen"] }, evidence: [evidence], status: "succeeded", blockers: [], validationIssues: [], fieldConfidence: {} },
    receipt: { id: "receipt-1", accountId: "a1", actionCardId: "card-1", confirmedVersion: 1, attempt: 1,
      provider: "android_calendar", status: "succeeded", targetRecordId: "event-1", startedAt: "2026-09-01T00:00:00Z", completedAt: "2026-09-01T00:00:01Z" },
    extraction: { id: "extraction-1", accountId: "a1", submissionId: "submission-1",
      messages: [{ id: "m1", order: 0, text: evidence.excerpt, confidence: 1 }], participants: [], entities: [],
      temporalExpressions: [], actionCandidates: [], evidenceSpans: [{ id: "span-1", messageId: "m1", excerpt: evidence.excerpt, confidence: 1 }],
      warnings: [], modelTrace: { model: "test", promptVersion: "v1" }, createdAt: "2026-09-01T00:00:00Z" },
    memories: ["Chen prefers email", "Chen promised to review the proposal"].map((assertion, i) => createMemory({
      accountId: "a1", subjectRefs: ["Chen"], type: i === 0 ? "preference" : "commitment", assertion,
      epistemicStatus: "fact", confidence: 1, sensitivity: "normal", sourceRefs: [`history:${i === 0 ? "email" : "proposal"}`],
      sourceEvidence: [{ sourceId: `history:${i === 0 ? "email" : "proposal"}`, excerpt: assertion, confidence: 1 }],
    })),
  };
}
