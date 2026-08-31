import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { createMemory } from "../src/domain/memory.js";
import type { ActionCard, ActionReceipt } from "../src/domain/model.js";
import { EvidenceBackedInsightGenerator } from "../src/insight/insight-generator.js";

describe("Evidence-backed insight generator", () => {
  it("uses startsAt for meeting follow-up timing", async () => {
    const output = await new EvidenceBackedInsightGenerator().generate({
      card: card("create_meeting", { title: "Coffee", startsAt: "2026-09-03T10:00:00+08:00" }),
      receipt: receipt("android_calendar"),
      memories: [],
    });
    assert.equal(output.items.find((item) => item.kind === "next_step")?.suggestedAt, "2026-09-03T10:00:00+08:00");
  });

  it("does not echo the just-created action memory as relationship background", async () => {
    const actionMemory = createMemory({
      accountId: "account-1",
      subjectRefs: ["Chen"],
      type: "profile",
      assertion: "已创建联系人 Chen",
      epistemicStatus: "fact",
      confidence: 1,
      sensitivity: "normal",
      sourceRefs: ["submission-1:receipt:receipt-1"],
      sourceEvidence: [{ sourceId: "submission-1:receipt:receipt-1", excerpt: "已创建联系人 Chen", confidence: 1 }],
    });
    const output = await new EvidenceBackedInsightGenerator().generate({
      card: card("create_contact", { displayName: "Chen", contactMethod: "chen@example.com" }),
      receipt: receipt("android_contacts"),
      memories: [actionMemory],
    });
    assert.equal(output.items.some((item) => item.kind === "relationship_context"), false);
    assert.equal(output.items.some((item) => item.kind === "next_step"), true);
  });
});

function card(type: ActionCard["type"], fields: Record<string, unknown>): ActionCard {
  return {
    id: "card-1",
    accountId: "account-1",
    submissionId: "submission-1",
    type,
    version: 1,
    fields,
    evidence: [{ sourceId: "submission-1", messageId: "m1", excerpt: "evidence", confidence: 0.9 }],
    fieldConfidence: {},
    validationIssues: [],
    targetAccountId: "target-1",
    status: "succeeded",
    blockers: [],
  };
}

function receipt(provider: ActionReceipt["provider"]): ActionReceipt & { status: "succeeded"; targetRecordId: string } {
  return {
    id: "receipt-1",
    accountId: "account-1",
    actionCardId: "card-1",
    confirmedVersion: 1,
    attempt: 1,
    status: "succeeded",
    provider,
    targetRecordId: "record-1",
    startedAt: "2026-09-01T00:00:00Z",
    completedAt: "2026-09-01T00:00:01Z",
  };
}
