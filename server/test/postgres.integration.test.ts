import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { describe, it } from "node:test";
import { KyselyMemoryRepository } from "../src/adapters/kysely-memory-repository.js";
import { createDatabase } from "../src/database/database.js";
import { createMemory } from "../src/domain/memory.js";

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
