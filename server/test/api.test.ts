import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { describe, it } from "node:test";
import { buildApp } from "../src/api/app.js";

describe("Action Card API", () => {
  it("isolates cards by account and executes only after confirmation", async () => {
    const app = buildApp(undefined, { allowInsecureAccountHeader: true });
    const cardId = randomUUID();
    const submissionId = randomUUID();
    const created = await app.inject({ method: "POST", url: "/v1/action-cards", headers: { "x-account-id": "a1" }, payload: {
      cardId, submissionId, type: "create_contact", fields: { displayName: "Chen", contactMethod: "chen@example.com", targetContactAccountId: "local" },
      targetAccountId: "local", evidence: [{ sourceId: submissionId, excerpt: "chen@example.com", confidence: 0.99 }],
    }});
    assert.equal(created.statusCode, 201);
    const card = created.json();
    const hidden = await app.inject({ method: "POST", url: `/v1/action-cards/${card.id}/confirm`, headers: { "x-account-id": "a2" }, payload: { expectedVersion: 1 } });
    assert.equal(hidden.statusCode, 404);
    const confirmed = await app.inject({ method: "POST", url: `/v1/action-cards/${card.id}/confirm`, headers: { "x-account-id": "a1" }, payload: { expectedVersion: 1 } });
    assert.equal(confirmed.statusCode, 200);
    const repeatedConfirmation = await app.inject({ method: "POST", url: `/v1/action-cards/${card.id}/confirm`, headers: { "x-account-id": "a1" }, payload: { expectedVersion: 1 } });
    assert.deepEqual(repeatedConfirmation.json(), confirmed.json());
    const receiptId = randomUUID();
    const receipt = await app.inject({ method: "POST", url: `/v1/action-cards/${card.id}/receipts`, headers: { "x-account-id": "a1" }, payload: { receiptId, status: "succeeded", targetRecordId: "contact-42" } });
    assert.equal(receipt.statusCode, 201);
    assert.equal(receipt.json().id, receiptId);
    assert.equal(receipt.json().confirmedVersion, 1);
    const repeated = await app.inject({ method: "POST", url: `/v1/action-cards/${card.id}/receipts`, headers: { "x-account-id": "a1" }, payload: { receiptId, status: "succeeded", targetRecordId: "contact-42" } });
    assert.equal(repeated.statusCode, 201);
    assert.deepEqual(repeated.json(), receipt.json());
    await app.close();
  });

  it("rejects stale card edits instead of applying a retry twice", async () => {
    const app = buildApp(undefined, { allowInsecureAccountHeader: true });
    const cardId = randomUUID();
    const submissionId = randomUUID();
    const created = await app.inject({ method: "POST", url: "/v1/action-cards", headers: { "x-account-id": "a1" }, payload: {
      cardId, submissionId, type: "create_contact", fields: { displayName: "Chen", contactMethod: "chen@example.com", targetContactAccountId: "local" },
      targetAccountId: "local", evidence: [{ sourceId: submissionId, excerpt: "chen@example.com", confidence: 0.99 }],
    }});
    const card = created.json();
    const editPayload = { expectedVersion: 1, fields: { ...card.fields, displayName: "Chen Wei" } };
    const edited = await app.inject({ method: "PATCH", url: `/v1/action-cards/${card.id}`, headers: { "x-account-id": "a1" }, payload: editPayload });
    assert.equal(edited.statusCode, 200);
    assert.equal(edited.json().version, 2);
    const repeated = await app.inject({ method: "PATCH", url: `/v1/action-cards/${card.id}`, headers: { "x-account-id": "a1" }, payload: editPayload });
    assert.equal(repeated.statusCode, 409);
    assert.equal(repeated.json().error, "card_version_conflict");
    const staleConfirmation = await app.inject({ method: "POST", url: `/v1/action-cards/${card.id}/confirm`, headers: { "x-account-id": "a1" }, payload: { expectedVersion: 1 } });
    assert.equal(staleConfirmation.statusCode, 409);
    assert.equal(staleConfirmation.json().error, "card_version_conflict");
    await app.close();
  });

  it("makes memory visible, correctable, deletable and account scoped", async () => {
    const app = buildApp(undefined, { allowInsecureAccountHeader: true });
    const created = await app.inject({ method: "POST", url: "/v1/memories", headers: { "x-account-id": "a1" }, payload: {
      subjectRefs: ["contact-1"], type: "profile", assertion: "Works at A", epistemicStatus: "inference",
      confidence: 0.6, sensitivity: "normal", sourceRefs: ["message-1"],
    }});
    assert.equal(created.statusCode, 201);
    const memory = created.json();
    const hidden = await app.inject({ method: "PATCH", url: `/v1/memories/${memory.id}`, headers: { "x-account-id": "a2" }, payload: { assertion: "Works at B", sourceRef: "correction-1" } });
    assert.equal(hidden.statusCode, 404);
    const revised = await app.inject({ method: "PATCH", url: `/v1/memories/${memory.id}`, headers: { "x-account-id": "a1" }, payload: { assertion: "Works at B", sourceRef: "correction-1" } });
    assert.equal(revised.statusCode, 200);
    assert.equal(revised.json().epistemicStatus, "fact");
    const removed = await app.inject({ method: "DELETE", url: `/v1/memories/${revised.json().id}`, headers: { "x-account-id": "a1" } });
    assert.equal(removed.statusCode, 204);
    const listed = await app.inject({ method: "GET", url: "/v1/memories", headers: { "x-account-id": "a1" } });
    assert.deepEqual(listed.json().items, []);
    await app.close();
  });
});
