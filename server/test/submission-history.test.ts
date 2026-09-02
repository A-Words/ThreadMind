import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { describe, it } from "node:test";
import { buildApp } from "../src/api/app.ts";
import { InMemoryStore } from "../src/adapters/in-memory-store.ts";
import { prepareSubmission } from "../src/domain/submission.ts";
import type { ActionStatus, ScreenshotSubmission } from "../src/domain/model.ts";
import type { SubmissionHistoryPage } from "../src/domain/submission-history.ts";

function add(store: InMemoryStore, accountId: string, status: ScreenshotSubmission["status"], cardStatuses: ActionStatus[] = []) {
  const { submission } = prepareSubmission({ id: randomUUID(), accountId, source: "in_app", contentType: "image/png",
    image: Uint8Array.from([137, 80, 78, 71, 13, 10, 26, 10]), supplementalText: "private body" }, new Date("2026-09-01T00:00:00Z"));
  submission.status = status;
  store.submissions.set(submission.id, submission);
  for (const cardStatus of cardStatuses) {
    const id = randomUUID();
    store.cards.set(id, { id, accountId, submissionId: submission.id, type: "create_contact", version: 1,
      fields: {}, evidence: [], fieldConfidence: {}, validationIssues: [], blockers: [], status: cardStatus });
  }
  return submission;
}

describe("Submission history", () => {
  it("pages equal timestamps without duplicates and exposes only summary metadata", async () => {
    const store = new InMemoryStore();
    const expected = Array.from({ length: 5 }, () => add(store, "a", "ready", ["succeeded"])).map((s) => s.id).sort().reverse();
    add(store, "b", "uploaded"); add(store, "a", "deleted");
    const app = buildApp(store, { allowInsecureAccountHeader: true });
    try {
      const ids: string[] = [];
      let cursor: string | null = null;
      do {
        const response: { statusCode: number; body: string; json(): SubmissionHistoryPage } = await app.inject({ method: "GET", url: `/v1/submissions?limit=2${cursor ? `&cursor=${cursor}` : ""}`, headers: { "x-account-id": "a" } });
        assert.equal(response.statusCode, 200);
        const page: SubmissionHistoryPage = response.json();
        for (const item of page.items) {
          assert.deepEqual(Object.keys(item).sort(), ["actionCounts", "createdAt", "id", "source", "status", "updatedAt"]);
          assert.equal(item.actionCounts.succeeded, 1);
          ids.push(item.id);
        }
        assert.doesNotMatch(response.body, /private body|imageObjectPath|modelTrace/);
        cursor = page.nextCursor;
      } while (cursor);
      assert.deepEqual(ids, expected);
    } finally { await app.close(); }
  });

  it("selects analysis and action attention states but excludes finished and deleted records", async () => {
    const store = new InMemoryStore();
    const expected = [add(store, "a", "uploaded"), add(store, "a", "processing"), add(store, "a", "failed"),
      ...(["draft", "blocked", "ready", "confirmed", "executing"] as ActionStatus[]).map((status) => add(store, "a", "ready", [status]))];
    add(store, "a", "ready", ["succeeded", "cancelled", "failed"]); add(store, "b", "failed"); add(store, "a", "deleted", ["ready"]);
    const app = buildApp(store, { allowInsecureAccountHeader: true });
    try {
      const response = await app.inject({ method: "GET", url: "/v1/submissions?view=attention", headers: { "x-account-id": "a" } });
      assert.deepEqual(response.json().items.map((s: {id: string}) => s.id).sort(), expected.map((s) => s.id).sort());
    } finally { await app.close(); }
  });

  it("validates view, page size and cursor and requires identity", async () => {
    const app = buildApp(undefined, { allowInsecureAccountHeader: true });
    try {
      assert.equal((await app.inject({ method: "GET", url: "/v1/submissions" })).statusCode, 401);
      for (const query of ["limit=0", "limit=51", "limit=1.2", "view=invalid", "cursor=garbage", "cursor=" + Buffer.from('{}').toString("base64url")]) {
        assert.equal((await app.inject({ method: "GET", url: `/v1/submissions?${query}`, headers: { "x-account-id": "a" } })).statusCode, 400);
      }
      assert.deepEqual((await app.inject({ method: "GET", url: "/v1/submissions", headers: { "x-account-id": "a" } })).json(), { items: [], nextCursor: null });
    } finally { await app.close(); }
  });
});
