import { type Insertable, type Kysely, type Selectable } from "kysely";
import { deleteMemory, reviseMemory } from "../domain/memory.ts";
import type { MemoryRecord } from "../domain/model.ts";
import { retryTransient, withAccount } from "../database/account-transaction.ts";
import type { ThreadMindDatabase, MemoryRecordsTable } from "../database/schema.ts";
import type { MemoryRepository } from "./memory-repository.ts";

export class KyselyMemoryRepository implements MemoryRepository {
  constructor(private readonly database: Kysely<ThreadMindDatabase>) {}

  async listActive(accountId: string): Promise<MemoryRecord[]> {
    return retryTransient(() => withAccount(this.database, accountId, async (trx) => {
      const rows = await trx
        .selectFrom("threadmind.memory_records")
        .selectAll()
        .where("status", "=", "active")
        .orderBy("updated_at", "desc")
        .orderBy("id", "desc")
        .limit(100)
        .execute();
      return rows.map(toMemoryRecord);
    }));
  }

  async create(memory: MemoryRecord): Promise<MemoryRecord> {
    return retryTransient(() => withAccount(this.database, memory.accountId, async (trx) => {
      const existing = await trx
        .selectFrom("threadmind.memory_records")
        .selectAll()
        .where("id", "=", memory.id)
        .executeTakeFirst();
      if (existing) return toMemoryRecord(existing);
      const row = await trx
        .insertInto("threadmind.memory_records")
        .values(toMemoryRow(memory))
        .returningAll()
        .executeTakeFirstOrThrow();
      return toMemoryRecord(row);
    }));
  }

  async revise(accountId: string, id: string, assertion: string, sourceRef: string): Promise<MemoryRecord | undefined> {
    return retryTransient(() => withAccount(this.database, accountId, async (trx) => {
      const currentRow = await trx
        .selectFrom("threadmind.memory_records")
        .selectAll()
        .where("id", "=", id)
        .where("status", "=", "active")
        .forUpdate()
        .executeTakeFirst();
      if (!currentRow) {
        const existingRevision = await trx
          .selectFrom("threadmind.memory_records")
          .selectAll()
          .where("supersedes_id", "=", id)
          .where("status", "=", "active")
          .where("assertion", "=", assertion)
          .executeTakeFirst();
        return existingRevision ? toMemoryRecord(existingRevision) : undefined;
      }

      const [superseded, revised] = reviseMemory(toMemoryRecord(currentRow), assertion, sourceRef);
      const updated = await trx
        .updateTable("threadmind.memory_records")
        .set({ status: superseded.status, updated_at: superseded.updatedAt })
        .where("id", "=", superseded.id)
        .where("status", "=", "active")
        .returning("id")
        .executeTakeFirst();
      if (!updated) return undefined;

      const revisedRow = await trx
        .insertInto("threadmind.memory_records")
        .values(toMemoryRow(revised))
        .returningAll()
        .executeTakeFirstOrThrow();
      return toMemoryRecord(revisedRow);
    }));
  }

  async remove(accountId: string, id: string): Promise<boolean> {
    return retryTransient(() => withAccount(this.database, accountId, async (trx) => {
      const currentRow = await trx
        .selectFrom("threadmind.memory_records")
        .selectAll()
        .where("id", "=", id)
        .where("status", "=", "active")
        .forUpdate()
        .executeTakeFirst();
      if (!currentRow) {
        const alreadyDeleted = await trx
          .selectFrom("threadmind.memory_records")
          .select("id")
          .where("id", "=", id)
          .where("status", "=", "deleted")
          .executeTakeFirst();
        return alreadyDeleted !== undefined;
      }
      const deleted = deleteMemory(toMemoryRecord(currentRow));
      const result = await trx
        .updateTable("threadmind.memory_records")
        .set({ status: deleted.status, updated_at: deleted.updatedAt })
        .where("id", "=", id)
        .where("status", "=", "active")
        .returning("id")
        .executeTakeFirst();
      return result !== undefined;
    }));
  }

}

function toMemoryRow(memory: MemoryRecord): Insertable<MemoryRecordsTable> {
  return {
    id: memory.id,
    account_id: memory.accountId,
    subject_refs: JSON.stringify(memory.subjectRefs),
    memory_type: memory.type,
    assertion: memory.assertion,
    epistemic_status: memory.epistemicStatus,
    confidence: memory.confidence,
    sensitivity: memory.sensitivity,
    source_refs: JSON.stringify(memory.sourceRefs),
    version: memory.version,
    supersedes_id: memory.supersedesId ?? null,
    status: memory.status,
    created_at: memory.createdAt,
    updated_at: memory.updatedAt,
  };
}

function toMemoryRecord(row: Selectable<MemoryRecordsTable>): MemoryRecord {
  return {
    id: row.id,
    accountId: row.account_id,
    subjectRefs: row.subject_refs,
    type: row.memory_type,
    assertion: row.assertion,
    epistemicStatus: row.epistemic_status,
    confidence: Number(row.confidence),
    sensitivity: row.sensitivity,
    sourceRefs: row.source_refs,
    version: row.version,
    ...(row.supersedes_id ? { supersedesId: row.supersedes_id } : {}),
    status: row.status,
    createdAt: new Date(row.created_at).toISOString(),
    updatedAt: new Date(row.updated_at).toISOString(),
  };
}
