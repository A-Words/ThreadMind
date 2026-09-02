import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { it } from "node:test";
import { createDatabase } from "../src/database/database.ts";
import { withAccount } from "../src/database/account-transaction.ts";
import { KyselySubmissionRepository } from "../src/adapters/kysely-submission-repository.ts";
import { submissionHistoryInput } from "../src/domain/submission-history.ts";

it("history pagination, counts and forced RLS work against PostgreSQL", { skip: !process.env.THREADMIND_E2E_ACCOUNT_ID, timeout: 120_000 }, async () => {
  const accountId = process.env.THREADMIND_E2E_ACCOUNT_ID!;
  const db = createDatabase(process.env);
  const ids = Array.from({ length: 5 }, randomUUID).sort().reverse();
  const repository = new KyselySubmissionRepository(db);
  try {
    await withAccount(db, accountId, async (tx) => {
      await tx.insertInto("threadmind.screenshot_submissions").values(ids.map((id, index) => ({
        id, account_id: accountId, image_object_path: `${accountId}/${id}`, image_content_type: "image/png" as const,
        image_byte_size: 8, image_sha256: "a".repeat(64), supplemental_text: "integration:history-ui",
        submission_source: "in_app" as const, status: index === 4 ? "deleted" as const : "ready" as const,
        created_at: "2099-01-01T00:00:00.123456Z", updated_at: new Date("2099-01-01T00:00:01Z"),
        completed_at: new Date("2099-01-01T00:00:01Z"),
      }))).execute();
      await tx.insertInto("threadmind.action_cards").values(["ready", "ready", "succeeded"].map((status) => ({
        id: randomUUID(), account_id: accountId, submission_id: ids[0]!, action_type: "create_contact" as const,
        version: 1, fields: "{}", evidence: "[]", field_confidence: "{}", validation_issues: "[]", blockers: "[]",
        status: status as "ready" | "succeeded", confirmed_snapshot: status === "succeeded" ? "{}" : null, target_account_id: null, confirmed_at: null,
      }))).execute();
    });
    const first = await repository.list(accountId, { view: "all", limit: 2 });
    assert.deepEqual(first.items.map((s) => s.id), ids.slice(0, 2));
    assert.equal(first.items[0]?.actionCounts.ready, 2);
    assert.equal(first.items[0]?.actionCounts.succeeded, 1);
    const cursor = submissionHistoryInput.parse({ cursor: first.nextCursor }).cursor!;
    const second = await repository.list(accountId, { view: "all", limit: 2, cursor });
    assert.deepEqual(second.items.map((s) => s.id), ids.slice(2, 4));
    const attention = await repository.list(accountId, { view: "attention", limit: 1 });
    assert.equal(attention.items[0]?.id, ids[0]);
    assert.deepEqual((await repository.list(randomUUID(), { view: "all", limit: 50 })).items, []);
    const invisible = await withAccount(db, randomUUID(), (tx) => tx.selectFrom("threadmind.screenshot_submissions")
      .select("id").where("id", "in", ids).execute());
    assert.deepEqual(invisible, [], "forced RLS protects even queries without an account predicate");
  } finally {
    try { await withAccount(db, accountId, (tx) => tx.deleteFrom("threadmind.screenshot_submissions").where("id", "in", ids).execute()); }
    finally { await db.destroy(); }
  }
});
