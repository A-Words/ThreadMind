import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { describe, it } from "node:test";
import { buildApp } from "../src/api/app.js";
import { InMemoryAuthAdmin, type AuthAdmin } from "../src/account/auth-admin.js";
import { InMemorySensitiveSessionVerifier } from "../src/account/sensitive-session-verifier.js";
import { InMemoryStore } from "../src/adapters/in-memory-store.js";
import { InMemoryTemporaryImageStorage } from "../src/adapters/temporary-image-storage.js";

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
    const insights = await app.inject({ method: "GET", url: `/v1/insights?submissionId=${submissionId}`, headers: { "x-account-id": "a1" } });
    assert.equal(insights.statusCode, 200);
    assert.equal(insights.json().items.length, 1);
    assert.equal(insights.json().items[0].actionReceiptIds[0], receiptId);
    assert.equal(insights.json().items[0].items[0].epistemicStatus, "fact");
    assert.match(insights.json().items[0].items[0].evidence[0].excerpt, /contact-42/);
    const hiddenInsights = await app.inject({ method: "GET", url: `/v1/insights?submissionId=${submissionId}`, headers: { "x-account-id": "a2" } });
    assert.deepEqual(hiddenInsights.json().items, []);
    const memories = await app.inject({ method: "GET", url: "/v1/memories", headers: { "x-account-id": "a1" } });
    assert.deepEqual(memories.json().items, []);
    await app.close();
  });

  it("does not create formal insights for failed execution", async () => {
    const app = buildApp(undefined, { allowInsecureAccountHeader: true });
    const cardId = randomUUID();
    const submissionId = randomUUID();
    await app.inject({ method: "POST", url: "/v1/action-cards", headers: { "x-account-id": "a1" }, payload: {
      cardId, submissionId, type: "create_contact", fields: { displayName: "Chen", contactMethod: "chen@example.com", targetContactAccountId: "local" },
      targetAccountId: "local", evidence: [{ sourceId: submissionId, excerpt: "chen@example.com", confidence: 0.99 }],
    }});
    await app.inject({ method: "POST", url: `/v1/action-cards/${cardId}/confirm`, headers: { "x-account-id": "a1" }, payload: { expectedVersion: 1 } });
    const failed = await app.inject({ method: "POST", url: `/v1/action-cards/${cardId}/receipts`, headers: { "x-account-id": "a1" }, payload: {
      receiptId: randomUUID(), status: "failed", errorCode: "provider_error", errorMessage: "Provider unavailable",
    }});
    assert.equal(failed.statusCode, 201);
    const insights = await app.inject({ method: "GET", url: `/v1/insights?submissionId=${submissionId}`, headers: { "x-account-id": "a1" } });
    assert.deepEqual(insights.json().items, []);
    await app.close();
  });

  it("rejects stale card edits instead of applying a retry twice", async () => {
    const app = buildApp(undefined, { allowInsecureAccountHeader: true });
    const cardId = randomUUID();
    const submissionId = randomUUID();
    const created = await app.inject({ method: "POST", url: "/v1/action-cards", headers: { "x-account-id": "a1" }, payload: {
      cardId, submissionId, type: "create_contact", fields: { displayName: "Chen", contactMethod: "chen@example.com", targetContactAccountId: "local" },
      fieldConfidence: { displayName: 0.72, contactMethod: 0.99, targetContactAccountId: 1 },
      validationIssues: ["duplicate:contact-42"],
      targetAccountId: "local", evidence: [{ sourceId: submissionId, excerpt: "chen@example.com", confidence: 0.99 }],
    }});
    const card = created.json();
    assert.equal(card.status, "blocked");
    assert.equal(card.fieldConfidence.displayName, 0.72);
    const editPayload = {
      expectedVersion: 1,
      fields: { ...card.fields, displayName: "Chen Wei", targetContactAccountId: "work" },
      targetAccountId: "work",
      resolvedValidationIssues: ["duplicate:contact-42"],
    };
    const edited = await app.inject({ method: "PATCH", url: `/v1/action-cards/${card.id}`, headers: { "x-account-id": "a1" }, payload: editPayload });
    assert.equal(edited.statusCode, 200);
    assert.equal(edited.json().version, 2);
    assert.equal(edited.json().status, "ready");
    assert.equal(edited.json().fieldConfidence.displayName, 1);
    assert.equal(edited.json().targetAccountId, "work");
    assert.deepEqual(edited.json().validationIssues, []);
    const repeated = await app.inject({ method: "PATCH", url: `/v1/action-cards/${card.id}`, headers: { "x-account-id": "a1" }, payload: editPayload });
    assert.equal(repeated.statusCode, 409);
    assert.equal(repeated.json().error, "card_version_conflict");
    const staleConfirmation = await app.inject({ method: "POST", url: `/v1/action-cards/${card.id}/confirm`, headers: { "x-account-id": "a1" }, payload: { expectedVersion: 1 } });
    assert.equal(staleConfirmation.statusCode, 409);
    assert.equal(staleConfirmation.json().error, "card_version_conflict");
    await app.close();
  });

  it("makes memory visible, correctable, deletable and account scoped", async () => {
    const store = new InMemoryStore();
    const app = buildApp(store, { allowInsecureAccountHeader: true });
    const created = await app.inject({ method: "POST", url: "/v1/memories", headers: { "x-account-id": "a1" }, payload: {
      subjectRefs: ["contact-1"], type: "profile", assertion: "Works at A", epistemicStatus: "inference",
      confidence: 0.6, sensitivity: "normal", sourceRefs: ["message-1"],
      sourceEvidence: [{ sourceId: "message-1", excerpt: "Chen works at A", confidence: 0.9 }],
    }});
    assert.equal(created.statusCode, 201);
    const memory = created.json();
    const second = await app.inject({ method: "POST", url: "/v1/memories", headers: { "x-account-id": "a1" }, payload: {
      subjectRefs: ["contact-2"], type: "event", assertion: "Dinner next Friday", epistemicStatus: "fact",
      confidence: 0.9, sensitivity: "normal", sourceRefs: ["message-2"],
      sourceEvidence: [{ sourceId: "message-2", excerpt: "Let's have dinner next Friday", confidence: 0.95 }],
    }});
    assert.equal(second.statusCode, 201);
    const searched = await app.inject({ method: "GET", url: "/v1/memories?q=chen", headers: { "x-account-id": "a1" } });
    assert.deepEqual(searched.json().items.map((item: { id: string }) => item.id), [memory.id]);
    const filtered = await app.inject({ method: "GET", url: "/v1/memories?subjectRef=contact-2&type=event", headers: { "x-account-id": "a1" } });
    assert.deepEqual(filtered.json().items.map((item: { id: string }) => item.id), [second.json().id]);
    const future = await app.inject({ method: "GET", url: "/v1/memories?from=2999-01-01T00%3A00%3A00.000Z", headers: { "x-account-id": "a1" } });
    assert.deepEqual(future.json().items, []);
    assert.equal(memory.sourceEvidence[0].excerpt, "Chen works at A");
    const hidden = await app.inject({ method: "PATCH", url: `/v1/memories/${memory.id}`, headers: { "x-account-id": "a2" }, payload: { assertion: "Works at B", sourceRef: "correction-1" } });
    assert.equal(hidden.statusCode, 404);
    const revised = await app.inject({ method: "PATCH", url: `/v1/memories/${memory.id}`, headers: { "x-account-id": "a1" }, payload: { assertion: "Works at B", sourceRef: "correction-1" } });
    assert.equal(revised.statusCode, 200);
    assert.equal(revised.json().epistemicStatus, "fact");
    const removed = await app.inject({ method: "DELETE", url: `/v1/memories/${revised.json().id}`, headers: { "x-account-id": "a1" } });
    assert.equal(removed.statusCode, 204);
    const listed = await app.inject({ method: "GET", url: "/v1/memories", headers: { "x-account-id": "a1" } });
    assert.deepEqual(listed.json().items.map((item: { id: string }) => item.id), [second.json().id]);
    const revisedLineage = [...store.memories.values()].filter((item) => item.id === memory.id || item.id === revised.json().id);
    assert.equal(revisedLineage.every((item) => item.status === "deleted" && item.assertion === "[deleted]"), true);
    const cleared = await app.inject({ method: "DELETE", url: "/v1/memories", headers: { "x-account-id": "a1" } });
    assert.equal(cleared.json().cleared, 1);
    const afterClear = await app.inject({ method: "GET", url: "/v1/memories", headers: { "x-account-id": "a1" } });
    assert.deepEqual(afterClear.json().items, []);
    assert.equal([...store.memories.values()].every((item) => item.status === "deleted" && item.assertion === "[deleted]"), true);
    await app.close();
  });
});

describe("Submission API", () => {
  it("stores one account-scoped submission, enqueues once and hides the storage handle", async () => {
    const store = new InMemoryStore();
    const temporaryImages = new InMemoryTemporaryImageStorage();
    const app = buildApp(store, { allowInsecureAccountHeader: true, temporaryImageStorage: temporaryImages });
    const submissionId = randomUUID();
    const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1]);
    const request = multipartPayload({ submissionId, source: "android_share", supplementalText: "客户陈先生" }, png, "image/png");
    const created = await app.inject({ method: "POST", url: "/v1/submissions", headers: { "x-account-id": "a1", "content-type": request.contentType }, payload: request.body });
    assert.equal(created.statusCode, 202);
    assert.equal(created.json().id, submissionId);
    assert.equal(created.json().status, "uploaded");
    assert.equal(created.json().imageObjectPath, undefined);
    assert.equal(created.json().imageSha256, undefined);
    assert.equal(store.jobs.size, 1);

    const repeated = await app.inject({ method: "POST", url: "/v1/submissions", headers: { "x-account-id": "a1", "content-type": request.contentType }, payload: request.body });
    assert.deepEqual(repeated.json(), created.json());
    assert.equal(store.jobs.size, 1);

    const hidden = await app.inject({ method: "GET", url: `/v1/submissions/${submissionId}`, headers: { "x-account-id": "a2" } });
    assert.equal(hidden.statusCode, 404);
    const visible = await app.inject({ method: "GET", url: `/v1/submissions/${submissionId}`, headers: { "x-account-id": "a1" } });
    assert.deepEqual(visible.json(), created.json());
    const action = await app.inject({ method: "POST", url: "/v1/action-cards", headers: { "x-account-id": "a1" }, payload: {
      cardId: randomUUID(), submissionId, type: "create_contact",
      fields: { displayName: "Chen", contactMethod: "chen@example.com", targetContactAccountId: "local" },
      targetAccountId: "local", evidence: [{ sourceId: submissionId, excerpt: "chen@example.com", confidence: 0.99 }],
    }});
    assert.equal(action.statusCode, 201);
    const cards = await app.inject({ method: "GET", url: `/v1/submissions/${submissionId}/action-cards`, headers: { "x-account-id": "a1" } });
    assert.equal(cards.statusCode, 200);
    assert.deepEqual(cards.json().items.map((item: { id: string }) => item.id), [action.json().id]);
    const hiddenCards = await app.inject({ method: "GET", url: `/v1/submissions/${submissionId}/action-cards`, headers: { "x-account-id": "a2" } });
    assert.equal(hiddenCards.statusCode, 404);

    const conflictingRequest = multipartPayload({ submissionId, source: "android_share", supplementalText: "客户陈先生" }, Buffer.concat([png, Buffer.from([2])]), "image/png");
    const conflict = await app.inject({ method: "POST", url: "/v1/submissions", headers: { "x-account-id": "a1", "content-type": conflictingRequest.contentType }, payload: conflictingRequest.body });
    assert.equal(conflict.statusCode, 409);
    assert.equal(conflict.json().error, "submission_conflict");
    const memory = await app.inject({ method: "POST", url: "/v1/memories", headers: { "x-account-id": "a1" }, payload: {
      subjectRefs: ["contact-1"], type: "profile", assertion: "Chen works at A", epistemicStatus: "fact",
      confidence: 0.9, sensitivity: "normal", sourceRefs: [`${submissionId}:e1`],
      sourceEvidence: [{ sourceId: `${submissionId}:e1`, excerpt: "Works at A", confidence: 0.9 }],
    }});
    assert.equal(memory.statusCode, 201);
    const hiddenDelete = await app.inject({ method: "DELETE", url: `/v1/submissions/${submissionId}`, headers: { "x-account-id": "a2" } });
    assert.equal(hiddenDelete.statusCode, 204);
    assert.equal(store.submissions.has(submissionId), true);
    const removed = await app.inject({ method: "DELETE", url: `/v1/submissions/${submissionId}`, headers: { "x-account-id": "a1" } });
    assert.equal(removed.statusCode, 204);
    assert.equal(temporaryImages.has(`a1/${submissionId}`), false);
    assert.equal(store.submissions.has(submissionId), false);
    assert.equal([...store.jobs.values()].some((job) => job.aggregateId === submissionId), false);
    assert.equal([...store.cards.values()].some((card) => card.submissionId === submissionId), false);
    assert.equal(store.memories.get(memory.json().id)?.assertion, "[deleted]");
    const repeatedDelete = await app.inject({ method: "DELETE", url: `/v1/submissions/${submissionId}`, headers: { "x-account-id": "a1" } });
    assert.equal(repeatedDelete.statusCode, 204);
    await app.close();
  });
});

describe("Account data API", () => {
  it("exports only the current account without storage handles or credentials", async () => {
    const sensitiveSessions = new InMemorySensitiveSessionVerifier();
    const app = buildApp(undefined, { allowInsecureAccountHeader: true, sensitiveSessionVerifier: sensitiveSessions });
    const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1]);
    const submissionIds = { a1: randomUUID(), a2: randomUUID() };
    for (const account of ["a1", "a2"] as const) {
      const upload = multipartPayload({ submissionId: submissionIds[account], source: "in_app", supplementalText: `export:${account}` }, png, "image/png");
      const created = await app.inject({
        method: "POST", url: "/v1/submissions",
        headers: { "x-account-id": account, "content-type": upload.contentType }, payload: upload.body,
      });
      assert.equal(created.statusCode, 202);
      const sourceId = `${submissionIds[account]}:e1`;
      const memory = await app.inject({ method: "POST", url: "/v1/memories", headers: { "x-account-id": account }, payload: {
        subjectRefs: [`contact-${account}`], type: "profile", assertion: `Memory ${account}`,
        epistemicStatus: "fact", confidence: 1, sensitivity: "normal", sourceRefs: [sourceId],
        sourceEvidence: [{ sourceId, excerpt: `Evidence ${account}`, confidence: 1 }],
      }});
      assert.equal(memory.statusCode, 201);
    }

    const response = await app.inject({ method: "GET", url: "/v1/account/export", headers: { "x-account-id": "a1" } });
    assert.equal(response.statusCode, 200);
    const exported = response.json();
    assert.equal(exported.format, "threadmind-export-v1");
    assert.equal(exported.accountId, "a1");
    assert.deepEqual(exported.submissions.map((item: { id: string }) => item.id), [submissionIds.a1]);
    assert.equal(exported.submissions[0].imageObjectPath, undefined);
    assert.equal(exported.submissions[0].imageSha256, undefined);
    assert.deepEqual(exported.memories.map((item: { assertion: string }) => item.assertion), ["Memory a1"]);
    assert.equal(JSON.stringify(exported).includes("Memory a2"), false);
    assert.deepEqual(sensitiveSessions.checks, [{ accountId: "a1", sessionId: undefined }]);
    await app.close();
  });

  it("rejects sensitive account operations when the session is no longer active", async () => {
    const sensitiveSessions = new InMemorySensitiveSessionVerifier(() => false);
    const app = buildApp(undefined, { allowInsecureAccountHeader: true, sensitiveSessionVerifier: sensitiveSessions });
    const exported = await app.inject({ method: "GET", url: "/v1/account/export", headers: { "x-account-id": "a1" } });
    const deleted = await app.inject({ method: "DELETE", url: "/v1/account", headers: { "x-account-id": "a1" } });
    assert.equal(exported.statusCode, 401);
    assert.equal(deleted.statusCode, 401);
    assert.equal(sensitiveSessions.checks.length, 2);
    await app.close();
  });

  it("deletes only the current account across storage, auth and application data", async () => {
    const store = new InMemoryStore();
    const temporaryImages = new InMemoryTemporaryImageStorage();
    const authAdmin = new InMemoryAuthAdmin((accountId) => store.deleteAccount(accountId));
    const app = buildApp(store, { allowInsecureAccountHeader: true, authAdmin, temporaryImageStorage: temporaryImages });
    const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1]);
    const submissionIds = { a1: randomUUID(), a2: randomUUID() };

    for (const accountId of ["a1", "a2"] as const) {
      const submissionId = submissionIds[accountId];
      const upload = multipartPayload({ submissionId, source: "in_app" }, png, "image/png");
      assert.equal((await app.inject({
        method: "POST", url: "/v1/submissions",
        headers: { "x-account-id": accountId, "content-type": upload.contentType }, payload: upload.body,
      })).statusCode, 202);
      assert.equal((await app.inject({ method: "POST", url: "/v1/memories", headers: { "x-account-id": accountId }, payload: {
        subjectRefs: [`contact-${accountId}`], type: "profile", assertion: `Memory ${accountId}`,
        epistemicStatus: "fact", confidence: 1, sensitivity: "normal", sourceRefs: [submissionId],
        sourceEvidence: [{ sourceId: submissionId, excerpt: `Evidence ${accountId}`, confidence: 1 }],
      }})).statusCode, 201);
      const cardId = randomUUID();
      assert.equal((await app.inject({ method: "POST", url: "/v1/action-cards", headers: { "x-account-id": accountId }, payload: {
        cardId, submissionId, type: "create_contact",
        fields: { displayName: accountId, contactMethod: `${accountId}@example.com`, targetContactAccountId: "local" },
        targetAccountId: "local", evidence: [{ sourceId: submissionId, excerpt: `${accountId}@example.com`, confidence: 1 }],
      }})).statusCode, 201);
      assert.equal((await app.inject({ method: "POST", url: `/v1/action-cards/${cardId}/confirm`, headers: { "x-account-id": accountId }, payload: { expectedVersion: 1 } })).statusCode, 200);
      assert.equal((await app.inject({ method: "POST", url: `/v1/action-cards/${cardId}/receipts`, headers: { "x-account-id": accountId }, payload: {
        receiptId: randomUUID(), status: "succeeded", targetRecordId: `contact-${accountId}`,
      }})).statusCode, 201);
    }

    const deleted = await app.inject({ method: "DELETE", url: "/v1/account", headers: { "x-account-id": "a1" } });
    assert.equal(deleted.statusCode, 204);
    assert.deepEqual(authAdmin.deletedUserIds, ["a1"]);
    assert.equal(temporaryImages.has(`a1/${submissionIds.a1}`), false);
    assert.equal(temporaryImages.has(`a2/${submissionIds.a2}`), true);
    assert.equal([...store.submissions.values()].some((item) => item.accountId === "a1"), false);
    assert.equal([...store.jobs.values()].some((item) => item.accountId === "a1"), false);
    assert.equal([...store.cards.values()].some((item) => item.accountId === "a1"), false);
    assert.equal(store.receipts.some((item) => item.accountId === "a1"), false);
    assert.equal([...store.memories.values()].some((item) => item.accountId === "a1"), false);
    assert.equal([...store.insights.values()].some((item) => item.accountId === "a1"), false);
    assert.equal([...store.submissions.values()].some((item) => item.accountId === "a2"), true);
    assert.equal([...store.memories.values()].some((item) => item.accountId === "a2"), true);
    assert.equal([...store.insights.values()].some((item) => item.accountId === "a2"), true);

    const repeated = await app.inject({ method: "DELETE", url: "/v1/account", headers: { "x-account-id": "a1" } });
    assert.equal(repeated.statusCode, 204);
    assert.deepEqual(authAdmin.deletedUserIds, ["a1"]);
    await app.close();
  });

  it("does not report account deletion success when the auth-admin step fails", async () => {
    const store = new InMemoryStore();
    const temporaryImages = new InMemoryTemporaryImageStorage();
    const failingAuthAdmin: AuthAdmin = { async deleteUser() { throw new Error("auth_admin_unavailable"); } };
    const app = buildApp(store, { allowInsecureAccountHeader: true, authAdmin: failingAuthAdmin, temporaryImageStorage: temporaryImages });
    const submissionId = randomUUID();
    const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1]);
    const upload = multipartPayload({ submissionId, source: "in_app" }, png, "image/png");
    assert.equal((await app.inject({
      method: "POST", url: "/v1/submissions",
      headers: { "x-account-id": "a1", "content-type": upload.contentType }, payload: upload.body,
    })).statusCode, 202);

    const response = await app.inject({ method: "DELETE", url: "/v1/account", headers: { "x-account-id": "a1" } });
    assert.equal(response.statusCode, 500);
    assert.equal(store.submissions.has(submissionId), true);
    assert.equal(temporaryImages.has(`a1/${submissionId}`), false);
    await app.close();
  });
});

function multipartPayload(
  fields: Record<string, string>,
  file: Buffer,
  contentType: string,
): { body: Buffer; contentType: string } {
  const boundary = `threadmind-${randomUUID()}`;
  const chunks: Buffer[] = [];
  for (const [name, value] of Object.entries(fields)) {
    chunks.push(Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="${name}"\r\n\r\n${value}\r\n`));
  }
  chunks.push(Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="image"; filename="screenshot.png"\r\nContent-Type: ${contentType}\r\n\r\n`));
  chunks.push(file, Buffer.from(`\r\n--${boundary}--\r\n`));
  return { body: Buffer.concat(chunks), contentType: `multipart/form-data; boundary=${boundary}` };
}
