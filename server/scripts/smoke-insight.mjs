import assert from "node:assert/strict";
import { OpenAIResponsesInsightModel } from "../dist/src/insight/openai-responses-insight-model.js";
import { GroundedInsightGenerator } from "../dist/src/insight/grounded-insight-generator.js";

// Explicit, paid network smoke using synthetic data only. Does not enable the production generator.
const chosenModel = process.env.THREADMIND_INSIGHT_MODEL || process.env.THREADMIND_VISION_MODEL;
if (!process.env.OPENAI_API_KEY || !chosenModel) throw new Error("Configure OPENAI_API_KEY and an insight or vision model for this smoke test");
const model = new OpenAIResponsesInsightModel({ apiKey: process.env.OPENAI_API_KEY, model: chosenModel, baseUrl: process.env.OPENAI_BASE_URL });
const generator = new GroundedInsightGenerator(model);
const assertion = (id, text, type) => ({ id, accountId: "a1", subjectRefs: ["Chen"], type, assertion: text, epistemicStatus: "fact", confidence: 1,
  sensitivity: "normal", sourceRefs: [`history:${id}`], sourceEvidence: [{ sourceId: `history:${id}`, excerpt: text, confidence: 1 }], version: 1, status: "active",
  createdAt: "2026-09-01T00:00:00Z", updatedAt: "2026-09-01T00:00:00Z" });
const excerpt = "Chen: 请在周五的产品评审会之前，把最终方案通过邮件发给我。";
const result = await generator.generate({
  card: { id: "card-1", accountId: "a1", submissionId: "submission-1", type: "create_meeting", version: 1, status: "succeeded", blockers: [],
    fields: { title: "产品评审会", startsAt: "2026-09-11T10:00:00+08:00", attendees: ["Chen"] }, evidence: [{ sourceId: "submission-1", excerpt, confidence: 1 }], validationIssues: [], fieldConfidence: {} },
  receipt: { id: "receipt-1", accountId: "a1", actionCardId: "card-1", confirmedVersion: 1, attempt: 1, status: "succeeded", provider: "android_calendar", targetRecordId: "event-1", startedAt: "2026-09-04T00:00:00Z", completedAt: "2026-09-04T00:00:01Z" },
  extraction: { id: "extraction-1", accountId: "a1", submissionId: "submission-1", messages: [{ id: "m1", order: 0, text: excerpt, speaker: "Chen", confidence: 1 }], participants: [{ id: "p1", displayName: "Chen", evidenceRefs: ["span-1"], confidence: 1 }], entities: [], temporalExpressions: [], actionCandidates: [], evidenceSpans: [{ id: "span-1", messageId: "m1", excerpt, confidence: 1 }], warnings: [], modelTrace: { model: "synthetic", promptVersion: "v1" }, createdAt: "2026-09-04T00:00:00Z" },
  memories: [assertion("email", "Chen 偏好通过邮件接收正式方案。", "preference"), assertion("review", "Chen 承诺在产品评审会前审阅最终方案。", "commitment")],
});
console.log(JSON.stringify({ model: result.modelTrace.model, items: result.items.map(({kind,title,explanation,suggestedAction,suggestedAt,evidenceRefs}) => ({kind,title,explanation,suggestedAction,suggestedAt,evidenceRefs})) }, null, 2));
assert.ok(result.items.some((item) => item.kind === "next_step" && item.suggestedAction));
assert.ok(result.items.some((item) => item.evidenceRefs.some((ref) => ref.startsWith("history:")) && item.evidenceRefs.some((ref) => ref.startsWith("submission-1"))), "Must combine current context with history");
assert.ok(result.items.every((item) => !item.suggestedAt), "Before the meeting is not an exact scheduling time");
