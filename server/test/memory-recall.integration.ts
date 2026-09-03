import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { it } from "node:test";
import { KyselyMemoryRepository } from "../src/adapters/kysely-memory-repository.ts";
import { createDatabase } from "../src/database/database.ts";
import { withAccount } from "../src/database/account-transaction.ts";

it("PostgreSQL filters before limiting, enforces RLS, and recalls corrected memory", {
  skip: !process.env.DATABASE_URL || !process.env.THREADMIND_E2E_ACCOUNT_ID, timeout: 120_000,
}, async () => {
  const accountId = process.env.THREADMIND_E2E_ACCOUNT_ID!;
  const db = createDatabase(process.env);
  const repository = new KyselyMemoryRepository(db);
  const ids: string[] = Array.from({ length: 106 }, randomUUID);
  const submissionId = randomUUID();
  const subject = `recall:${randomUUID()}`;
  const oldId = ids[0]!;
  const query = { subjectRefs: [subject], submissionId };
  try {
    await withAccount(db, accountId, (tx) => tx.insertInto("threadmind.memory_records").values(ids.map((id, index) => ({
      id, account_id: accountId, subject_refs: JSON.stringify([index === 0 ? subject : `unrelated:${id}`]),
      memory_type: "preference" as const, assertion: "Synthetic recall fixture", epistemic_status: "fact" as const,
      confidence: 1, sensitivity: "normal" as const, source_refs: JSON.stringify([index === 1 ? `${submissionId}:e1` : `history:${id}`]),
      source_evidence: JSON.stringify([{ sourceId: index === 1 ? `${submissionId}:e1` : `history:${id}`, excerpt: "Synthetic evidence", confidence: 1 }]),
      version: 1, supersedes_id: null, status: "active" as const,
      created_at: index === 0 ? "2000-01-01T00:00:00Z" : "2099-01-01T00:00:00Z",
      updated_at: index === 0 ? "2000-01-01T00:00:00Z" : "2099-01-01T00:00:00Z",
    }))).execute());
    assert.equal((await repository.listActive(accountId)).some((m) => m.id === oldId), false);
    assert.deepEqual((await repository.recallActive(accountId, query)).map((m) => m.id), [ids[1], oldId]);
    assert.deepEqual(await repository.recallActive(randomUUID(), query), []);
    assert.deepEqual(await repository.recallActive(accountId, { subjectRefs: [], submissionId: `${submissionId}%` }), []);
    const revised = await repository.revise(accountId, oldId, "User corrected preference", "user:correction");
    ids.push(revised!.id);
    assert.deepEqual((await repository.recallActive(accountId, query)).map((m) => m.id), [ids[1], revised!.id]);
    await repository.remove(accountId, revised!.id);
    assert.deepEqual((await repository.recallActive(accountId, query)).map((m) => m.id), [ids[1]]);
  } finally {
    try { await withAccount(db, accountId, (tx) => tx.deleteFrom("threadmind.memory_records").where("id", "in", ids).execute()); }
    finally { await db.destroy(); }
  }
});
