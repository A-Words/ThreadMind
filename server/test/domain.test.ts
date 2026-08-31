import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { confirmCard, editCard, evaluateCard, recordExecution } from "../src/domain/action-card.js";
import { createInsightBundle } from "../src/domain/insight.js";
import { createMemory, deleteMemory, recallable, reviseMemory } from "../src/domain/memory.js";
import { prepareSubmission } from "../src/domain/submission.js";
import type { ActionCard } from "../src/domain/model.js";

const readyCard = (): ActionCard => evaluateCard({
  id: "card-1", accountId: "account-1", submissionId: "submission-1", type: "create_meeting", version: 1,
  fields: { title: "Coffee", startsAt: "2026-09-01T10:00:00+08:00", endsAt: "2026-09-01T11:00:00+08:00", timezone: "Asia/Taipei", targetCalendarId: "calendar-1" },
  evidence: [{ sourceId: "submission-1", messageId: "m1", excerpt: "下周二十点喝咖啡", confidence: 0.92 }],
  fieldConfidence: { title: 0.99, startsAt: 0.75, endsAt: 0.75, timezone: 1, targetCalendarId: 1 },
  validationIssues: [],
  targetAccountId: "calendar-account-1", status: "draft", blockers: [],
});
describe("Action Card invariants", () => {
  it("blocks incomplete cards and refuses confirmation", () => {
    const card = evaluateCard({ ...readyCard(), fields: {} });
    assert.equal(card.status, "blocked");
    assert.throws(() => confirmCard(card), { code: "card_not_ready" });
  });

  it("captures an immutable version and invalidates it after editing", () => {
    const confirmed = confirmCard(readyCard());
    assert.equal(confirmed.confirmedSnapshot?.version, 1);
    assert.equal(Object.isFrozen(confirmed.confirmedSnapshot?.fields), true);
    const edited = editCard(confirmed, { ...confirmed.fields, title: "Lunch" });
    assert.equal(edited.version, 2);
    assert.equal(edited.confirmedSnapshot, undefined);
  });

  it("blocks unresolved review issues and only clears explicitly resolved ones", () => {
    const ambiguous = evaluateCard({ ...readyCard(), validationIssues: ["ambiguous:start", "overlap:event-42"] });
    assert.equal(ambiguous.status, "blocked");
    const edited = editCard(
      ambiguous,
      { ...ambiguous.fields, startsAt: "2026-09-02T10:00:00+08:00" },
      ambiguous.evidence,
      ["ambiguous:start"],
    );
    assert.deepEqual(edited.validationIssues, ["overlap:event-42"]);
    assert.equal(edited.fieldConfidence.startsAt, 1);
    assert.equal(edited.status, "blocked");
    assert.throws(() => editCard(edited, edited.fields, edited.evidence, ["missing:issue"]), { code: "unknown_validation_issue" });
  });

  it("cannot execute an unconfirmed card and records failures without a target id", () => {
    assert.throws(() => recordExecution(readyCard(), { status: "succeeded", targetRecordId: "event-1" }, []), { code: "card_not_confirmed" });
    const result = recordExecution(confirmCard(readyCard()), { status: "failed", errorCode: "permission_denied" }, []);
    assert.equal(result.receipt.targetRecordId, undefined);
    assert.equal(result.card.status, "failed");
  });
});

describe("Memory and insight invariants", () => {
  it("versions corrections and filters deleted and superseded records", () => {
    const first = createMemory({
      accountId: "account-1", subjectRefs: ["contact-1"], type: "profile", assertion: "Works at A",
      epistemicStatus: "inference", confidence: 0.6, sensitivity: "normal", sourceRefs: ["message-1"],
      sourceEvidence: [{ sourceId: "message-1", excerpt: "Chen works at A", confidence: 0.9 }],
    });
    const [superseded, corrected] = reviseMemory(first, "Works at B", "user-correction-1");
    const deleted = deleteMemory(corrected);
    assert.deepEqual(recallable([superseded, deleted], "account-1"), []);
    assert.equal(corrected.epistemicStatus, "fact");
    assert.equal(corrected.supersedesId, first.id);
    assert.equal(corrected.sourceEvidence.at(-1)?.excerpt, "Works at B");
  });

  it("requires successful action receipts and evidence for formal insights", () => {
    const { receipt } = recordExecution(confirmCard(readyCard()), { status: "failed", errorCode: "provider_error" }, []);
    assert.throws(() => createInsightBundle({ accountId: "account-1", submissionId: "submission-1", receipts: [receipt], items: [], modelTrace: { model: "fake", promptVersion: "v1" } }), { code: "successful_action_required" });
  });
});

describe("Screenshot submission invariants", () => {
  it("accepts matching image signatures and rejects spoofed content types", () => {
    const png = Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1]);
    const prepared = prepareSubmission({ id: "submission-1", accountId: "account-1", image: png, contentType: "image/png", source: "in_app" });
    assert.equal(prepared.submission.imageByteSize, png.byteLength);
    assert.equal(prepared.submission.imageObjectPath, "account-1/submission-1");
    assert.throws(
      () => prepareSubmission({ id: "submission-2", accountId: "account-1", image: png, contentType: "image/jpeg", source: "in_app" }),
      { code: "image_type_mismatch" },
    );
  });
});
