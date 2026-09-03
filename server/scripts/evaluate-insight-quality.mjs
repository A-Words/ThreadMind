import assert from "node:assert/strict";
import { createMemory, reviseMemory, deleteMemory } from "../dist/src/domain/memory.js";
import { GroundedInsightGenerator } from "../dist/src/insight/grounded-insight-generator.js";
import { OpenAIResponsesInsightModel } from "../dist/src/insight/openai-responses-insight-model.js";
import { InsightService } from "../dist/src/insight/insight-service.js";
import { InMemoryStore } from "../dist/src/adapters/in-memory-store.js";
import { InMemoryMemoryRepository } from "../dist/src/adapters/in-memory-memory-repository.js";
import { InMemoryInsightRepository } from "../dist/src/adapters/in-memory-insight-repository.js";

const chosenModel = process.env.THREADMIND_INSIGHT_MODEL || process.env.THREADMIND_VISION_MODEL;
if (!process.env.OPENAI_API_KEY || !chosenModel) throw new Error("Configure OPENAI_API_KEY and a model for this paid synthetic evaluation");
const generator = new GroundedInsightGenerator(new OpenAIResponsesInsightModel({ apiKey: process.env.OPENAI_API_KEY, model: chosenModel, baseUrl: process.env.OPENAI_BASE_URL }));
const sourceKinds = (result) => result.items.flatMap((item) => item.evidenceRefs);
const nextStep = (result) => result.items.find((item) => item.kind === "next_step");
const records = [];

async function evaluate(name, input, verify) {
  const result = await generator.generate(input);
  assert.ok(nextStep(result)?.suggestedAction, `${name}: concrete next step required`);
  assert.ok(result.items.every((item) => item.explanation.length > 12), `${name}: generic short explanation`);
  assert.ok(!JSON.stringify(result).includes("复核本次更新和相关背景"), `${name}: legacy generic template`);
  console.log(JSON.stringify({ name, items: result.items.map(({ kind, title, suggestedAction, suggestedAt, evidenceRefs }) => ({ kind, title, suggestedAction, suggestedAt, evidenceRefs })) }));
  verify(result);
  records.push({ name, items: result.items.map(({ kind, title, explanation, suggestedAction, suggestedAt, evidenceRefs }) => ({ kind, title, explanation, suggestedAction, suggestedAt, evidenceRefs })) });
}

const preference = memory("preference", "Lin 偏好通过邮件接收正式材料。", "history:preference");
await evaluate("history_preference", fixture({ memories: [preference] }), (result) => {
  const refs = nextStep(result).evidenceRefs;
  assert.ok(refs.includes("history:preference") && refs.some((ref) => ref.startsWith("contact:")) && refs.includes("submission-1"));
  assert.match(nextStep(result).suggestedAction, /邮件|@/);
});

const commitment = memory("commitment", "Lin 承诺在评审会前反馈预算问题。", "history:commitment");
await evaluate("prior_commitment", fixture({ memories: [commitment] }), (result) => {
  assert.ok(sourceKinds(result).includes("history:commitment"));
  assert.match(`${nextStep(result).title}${nextStep(result).suggestedAction}`, /预算|反馈/);
});

const oldPreference = memory("preference", "Lin 偏好电话沟通。", "history:old");
const [, correctedPreference] = reviseMemory(oldPreference, "Lin 现在偏好通过邮件沟通。", "user:correction");
await evaluate("corrected_memory", fixture({ memories: [correctedPreference] }), (result) => {
  assert.ok(sourceKinds(result).includes("user:correction"));
  assert.equal(JSON.stringify(result).includes("电话沟通"), false);
  assert.match(nextStep(result).suggestedAction, /邮件|@/);
});

await evaluate("deleted_memory", fixture({ memories: [deleteMemory(preference)] }), (result) => {
  assert.equal(sourceKinds(result).some((ref) => ref.startsWith("history:")), false);
  assert.ok(sourceKinds(result).some((ref) => ref.startsWith("contact:")));
});

await evaluate("same_name_ambiguity", fixture({ ambiguous: true, memories: [preference] }), (result) => {
  const action = `${nextStep(result).title}${nextStep(result).explanation}${nextStep(result).suggestedAction}`;
  assert.match(action, /核对|确认|区分|身份|哪一位/);
  assert.ok(nextStep(result).evidenceRefs.filter((ref) => ref.startsWith("contact:")).length >= 2);
});

await evaluate("insufficient_evidence", fixture({ permissionStatus: "denied", memories: [], text: "对方：之后再联系。" }), (result) => {
  const action = `${nextStep(result).title}${nextStep(result).explanation}${nextStep(result).suggestedAction}`;
  assert.match(action, /确认|补充|核对|询问|澄清/);
  assert.equal(nextStep(result).suggestedAt, undefined);
  assert.equal(sourceKinds(result).some((ref) => ref.startsWith("contact:")), false);
});

let failedCalls = 0;
const store = new InMemoryStore();
const failedService = new InsightService(new InMemoryInsightRepository(store), new InMemoryMemoryRepository(store), { generate: async () => { failedCalls++; throw new Error("must not run"); } });
const failed = fixture({}).receipt;
assert.equal(await failedService.ensureForReceipt(fixture({}).card, { ...failed, status: "failed", targetRecordId: undefined }), undefined);
assert.equal(failedCalls, 0);
records.push({ name: "failed_execution", items: [], generatorCalls: failedCalls });

console.log(JSON.stringify({ model: chosenModel, passed: records.length, cases: records }, null, 2));

function memory(type, assertion, sourceId) {
  return createMemory({ accountId: "a1", subjectRefs: ["Lin"], type, assertion, epistemicStatus: "fact", confidence: 1,
    sensitivity: "normal", sourceRefs: [sourceId], sourceEvidence: [{ sourceId, excerpt: assertion, confidence: 1 }] });
}

function fixture({ ambiguous = false, permissionStatus = "granted", memories = [], text = "Lin：请在下周评审会前把最终方案发给我。" }) {
  const contactRecords = permissionStatus === "granted" ? [
    { providerContactId: "contact-1", displayName: "Lin", emailAddresses: ["lin@example.com"], phoneNumbers: [], organization: "Acme", jobTitle: "采购负责人",
      matchBasis: ambiguous ? "exact_email" : "provider_record_id", identityStatus: ambiguous ? "ambiguous" : "confirmed_target" },
    ...(ambiguous ? [{ providerContactId: "contact-2", displayName: "Lin", emailAddresses: ["lin@example.com"], phoneNumbers: [], organization: "Beta",
      jobTitle: "工程师", matchBasis: "exact_email", identityStatus: "ambiguous" }] : []),
  ] : [];
  const evidence = { sourceId: "submission-1", excerpt: text, confidence: 1 };
  return {
    card: { id: "card-1", accountId: "a1", submissionId: "submission-1", type: "create_meeting", version: 1,
      fields: { title: "方案评审", startsAt: "2026-09-11T10:00:00+08:00", attendees: ["lin@example.com"] }, evidence: [evidence],
      fieldConfidence: {}, validationIssues: [], status: "succeeded", blockers: [] },
    receipt: { id: "receipt-1", accountId: "a1", actionCardId: "card-1", confirmedVersion: 1, attempt: 1, status: "succeeded",
      provider: "android_calendar", targetRecordId: "event-1", startedAt: "2026-09-04T00:00:00Z", completedAt: "2026-09-04T00:00:01Z",
      contactContext: { source: "android_contacts_provider", capturedAt: "2026-09-04T00:00:01Z", permissionStatus,
        queries: permissionStatus === "denied" ? [{ kind: "email", value: "lin@example.com" }] : [{ kind: ambiguous ? "email" : "target_record_id", value: ambiguous ? "lin@example.com" : "contact-1" }], records: contactRecords } },
    extraction: { id: "extraction-1", accountId: "a1", submissionId: "submission-1", messages: [{ id: "m1", order: 0, text, speaker: "Lin", confidence: 1 }],
      participants: [], entities: [], temporalExpressions: [], actionCandidates: [], evidenceSpans: [{ id: "span-1", messageId: "m1", excerpt: text, confidence: 1 }],
      warnings: [], modelTrace: { model: "synthetic", promptVersion: "quality-v1" }, createdAt: "2026-09-04T00:00:00Z" }, memories,
  };
}
