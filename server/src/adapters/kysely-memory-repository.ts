import { type Insertable, type Kysely, type Selectable, sql } from "kysely";
import { deleteMemory, reviseMemory } from "../domain/memory.ts";
import type { MemoryRecord } from "../domain/model.ts";
import { retryTransient, withAccount } from "../database/account-transaction.ts";
import type { ThreadMindDatabase, MemoryRecordsTable } from "../database/schema.ts";
import type { MemoryRepository, MemorySearchQuery } from "./memory-repository.ts";
import { MEMORY_RECALL_LIMIT, type MemoryRecallQuery } from "./memory-repository.ts";

export class KyselyMemoryRepository implements MemoryRepository {
  constructor(private readonly database: Kysely<ThreadMindDatabase>) {}

  async recallActive(accountId: string, query: MemoryRecallQuery): Promise<MemoryRecord[]> {
    return retryTransient(() => withAccount(this.database, accountId, async (trx) => {
      const rows = await trx.selectFrom("threadmind.memory_records").selectAll()
        .where("status", "=", "active")
        .where(sql<boolean>`exists (
          select 1 from jsonb_array_elements(source_evidence) as evidence
          where source_refs ? (evidence->>'sourceId') and btrim(evidence->>'excerpt') <> ''
        )`)
        .where(sql<boolean>`(
          subject_refs ?| ${sql.val(query.subjectRefs)}::text[]
          or exists (
            select 1 from jsonb_array_elements_text(source_refs) as source(value)
            where source.value = ${query.submissionId}
              or left(source.value, ${query.submissionId.length + 1}) = ${`${query.submissionId}:`}
          )
        )`)
        .orderBy("updated_at", "desc").orderBy("id", "desc")
        .limit(MEMORY_RECALL_LIMIT).execute();
      return rows.map(toMemoryRecord);
    }));
  }

  async listActive(accountId: string, query: MemorySearchQuery = {}): Promise<MemoryRecord[]> {
    return retryTransient(() => withAccount(this.database, accountId, async (trx) => {
      let selection = trx
        .selectFrom("threadmind.memory_records")
        .selectAll()
        .where("status", "=", "active");
      if (query.search?.trim()) {
        const pattern = `%${query.search.trim()}%`;
        selection = selection.where((expression) => expression.or([
          expression("assertion", "ilike", pattern),
          sql<boolean>`${sql.ref("source_evidence")}::text ilike ${pattern}`,
        ]));
      }
      if (query.subjectRef) {
        selection = selection.where(sql<boolean>`${sql.ref("subject_refs")} @> ${JSON.stringify([query.subjectRef])}::jsonb`);
      }
      if (query.type) selection = selection.where("memory_type", "=", query.type);
      if (query.createdFrom) selection = selection.where("created_at", ">=", new Date(query.createdFrom));
      if (query.createdTo) selection = selection.where("created_at", "<=", new Date(query.createdTo));
      const rows = await selection
        .orderBy("updated_at", "desc")
        .orderBy("id", "desc")
        .limit(query.limit ?? 100)
        .execute();
      return rows.map(toMemoryRecord);
    }));
  }

  async listAll(accountId: string): Promise<MemoryRecord[]> {
    return retryTransient(() => withAccount(this.database, accountId, async (trx) => {
      const rows = await trx
        .selectFrom("threadmind.memory_records")
        .selectAll()
        .orderBy("updated_at", "desc")
        .orderBy("id", "desc")
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
      const rows = await trx
        .selectFrom("threadmind.memory_records")
        .selectAll()
        .forUpdate()
        .execute();
      const records = rows.map(toMemoryRecord);
      const current = records.find((memory) => memory.id === id);
      if (!current) return false;
      if (current.status === "deleted") return true;
      for (const memory of lineage(records, id)) {
        const deleted = deleteMemory(memory);
        await trx
          .updateTable("threadmind.memory_records")
          .set(toMemoryContentUpdate(deleted))
          .where("id", "=", memory.id)
          .executeTakeFirst();
      }
      return true;
    }));
  }

  async clear(accountId: string): Promise<number> {
    return retryTransient(() => withAccount(this.database, accountId, async (trx) => {
      const rows = await trx
        .selectFrom("threadmind.memory_records")
        .selectAll()
        .where("status", "!=", "deleted")
        .forUpdate()
        .execute();
      for (const row of rows) {
        const deleted = deleteMemory(toMemoryRecord(row));
        await trx.updateTable("threadmind.memory_records")
          .set(toMemoryContentUpdate(deleted))
          .where("id", "=", deleted.id)
          .executeTakeFirst();
      }
      return rows.length;
    }));
  }

  async removeSubmissionSource(accountId: string, submissionId: string): Promise<number> {
    return retryTransient(() => withAccount(this.database, accountId, async (trx) => {
      const rows = await trx.selectFrom("threadmind.memory_records").selectAll().forUpdate().execute();
      let changed = 0;
      for (const row of rows) {
        const memory = toMemoryRecord(row);
        if (memory.status === "deleted") continue;
        const sourceRefs = memory.sourceRefs.filter((source) => !belongsToSubmission(source, submissionId));
        if (sourceRefs.length === memory.sourceRefs.length) continue;
        const updated = sourceRefs.length === 0
          ? deleteMemory(memory)
          : {
              ...memory,
              sourceRefs,
              sourceEvidence: memory.sourceEvidence.filter((evidence) => sourceRefs.includes(evidence.sourceId)),
              updatedAt: new Date().toISOString(),
            };
        await trx.updateTable("threadmind.memory_records")
          .set(toMemoryContentUpdate(updated))
          .where("id", "=", memory.id)
          .executeTakeFirst();
        changed += 1;
      }
      return changed;
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
    source_evidence: JSON.stringify(memory.sourceEvidence),
    version: memory.version,
    supersedes_id: memory.supersedesId ?? null,
    status: memory.status,
    created_at: memory.createdAt,
    updated_at: memory.updatedAt,
  };
}

export function toMemoryRecord(row: Selectable<MemoryRecordsTable>): MemoryRecord {
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
    sourceEvidence: row.source_evidence,
    version: row.version,
    ...(row.supersedes_id ? { supersedesId: row.supersedes_id } : {}),
    status: row.status,
    createdAt: new Date(row.created_at).toISOString(),
    updatedAt: new Date(row.updated_at).toISOString(),
  };
}

function toMemoryContentUpdate(memory: MemoryRecord) {
  return {
    subject_refs: JSON.stringify(memory.subjectRefs),
    assertion: memory.assertion,
    epistemic_status: memory.epistemicStatus,
    confidence: memory.confidence,
    sensitivity: memory.sensitivity,
    source_refs: JSON.stringify(memory.sourceRefs),
    source_evidence: JSON.stringify(memory.sourceEvidence),
    status: memory.status,
    updated_at: memory.updatedAt,
  };
}

function lineage(records: MemoryRecord[], startId: string): MemoryRecord[] {
  const ids = new Set([startId]);
  let changed = true;
  while (changed) {
    changed = false;
    for (const record of records) {
      if (ids.has(record.id) || (record.supersedesId && ids.has(record.supersedesId))) {
        if (!ids.has(record.id)) changed = true;
        ids.add(record.id);
        if (record.supersedesId && !ids.has(record.supersedesId)) changed = true;
        if (record.supersedesId) ids.add(record.supersedesId);
      }
    }
  }
  return records.filter((record) => ids.has(record.id));
}

function belongsToSubmission(sourceRef: string, submissionId: string): boolean {
  return sourceRef === submissionId || sourceRef.startsWith(`${submissionId}:`);
}
