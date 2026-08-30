import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { describe, it } from "node:test";
import { KyselyActionRepository } from "../src/adapters/kysely-action-repository.js";
import { KyselyMemoryRepository } from "../src/adapters/kysely-memory-repository.js";
import { KyselySubmissionRepository } from "../src/adapters/kysely-submission-repository.js";
import { createDatabase } from "../src/database/database.js";
import { confirmCard, evaluateCard } from "../src/domain/action-card.js";
import { createMemory } from "../src/domain/memory.js";
import { prepareSubmission } from "../src/domain/submission.js";

const accountId = process.env.THREADMIND_E2E_ACCOUNT_ID;
const enabled = Boolean(process.env.DATABASE_URL && accountId);

describe("PostgreSQL Memory Repository", { skip: !enabled }, () => {
  it("persists revisions and enforces account-scoped RLS", async () => {
    const database = createDatabase(process.env);
    const repository = new KyselyMemoryRepository(database);
    const sourceRef = `integration:${randomUUID()}`;
    try {
      const created = await repository.create(createMemory({
        accountId: accountId!,
        subjectRefs: ["contact:test"],
        type: "profile",
        assertion: "Integration memory A",
        epistemicStatus: "inference",
        confidence: 0.6,
        sensitivity: "normal",
        sourceRefs: [sourceRef],
      }));
      assert.equal((await repository.listActive(accountId!)).some((item) => item.id === created.id), true);
      assert.equal((await repository.listActive(randomUUID())).some((item) => item.id === created.id), false);

      const revised = await repository.revise(accountId!, created.id, "Integration memory B", `${sourceRef}:correction`);
      assert.equal(revised?.version, 2);
      assert.equal(revised?.epistemicStatus, "fact");
      const repeatedRevision = await repository.revise(accountId!, created.id, "Integration memory B", `${sourceRef}:retry`);
      assert.equal(repeatedRevision?.id, revised?.id);
      assert.equal(await repository.remove(randomUUID(), revised!.id), false);
      assert.equal(await repository.remove(accountId!, revised!.id), true);
      assert.equal(await repository.remove(accountId!, revised!.id), true);
      assert.equal((await repository.listActive(accountId!)).some((item) => item.id === revised!.id), false);
    } finally {
      await database.destroy();
    }
  });
});

describe("PostgreSQL Action Repository", { skip: !enabled }, () => {
  it("persists account-scoped card transitions and idempotent receipts", async () => {
    const database = createDatabase(process.env);
    const repository = new KyselyActionRepository(database);
    const submissions = new KyselySubmissionRepository(database);
    const cardId = randomUUID();
    const submissionId = randomUUID();
    const receiptId = randomUUID();
    try {
      const prepared = prepareSubmission({
        id: submissionId,
        accountId: accountId!,
        image: Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1]),
        contentType: "image/png",
        source: "in_app",
        supplementalText: "integration:test",
      });
      const submission = await submissions.createWithJob(prepared.submission, prepared.job);
      assert.equal(submission.status, "uploaded");
      assert.equal(await submissions.find(randomUUID(), submissionId), undefined);
      const created = await repository.create(evaluateCard({
        id: cardId,
        accountId: accountId!,
        submissionId,
        type: "create_contact",
        version: 1,
        fields: { displayName: "Integration Contact", contactMethod: "integration@example.com", targetContactAccountId: "integration:test" },
        evidence: [{ sourceId: submissionId, excerpt: "integration@example.com", confidence: 1 }],
        fieldConfidence: { displayName: 1, contactMethod: 1, targetContactAccountId: 1 },
        validationIssues: [],
        targetAccountId: "integration:test",
        status: "draft",
        blockers: [],
      }));
      assert.equal(created.status, "ready");
      assert.equal(await repository.find(randomUUID(), cardId), undefined);
      const confirmed = await repository.mutate(accountId!, cardId, confirmCard);
      assert.equal(confirmed?.status, "confirmed");
      const recorded = await repository.recordExecution(accountId!, cardId, receiptId, { status: "succeeded", targetRecordId: "integration:contact" });
      assert.equal(recorded?.receipt.id, receiptId);
      assert.equal(recorded?.card.status, "succeeded");
      const repeated = await repository.recordExecution(accountId!, cardId, receiptId, { status: "succeeded", targetRecordId: "integration:contact" });
      assert.deepEqual(repeated, recorded);
    } finally {
      await database.destroy();
    }
  });
});
