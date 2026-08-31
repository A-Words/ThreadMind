import { deleteMemory, reviseMemory } from "../domain/memory.ts";
import type { MemoryRecord } from "../domain/model.ts";
import type { MemoryRepository, MemorySearchQuery } from "./memory-repository.ts";
import type { InMemoryStore } from "./in-memory-store.ts";

export class InMemoryMemoryRepository implements MemoryRepository {
  constructor(private readonly store: InMemoryStore) {}

  async listActive(accountId: string, query: MemorySearchQuery = {}): Promise<MemoryRecord[]> {
    const search = query.search?.trim().toLocaleLowerCase();
    return this.store.activeMemories(accountId)
      .filter((memory) => !query.subjectRef || memory.subjectRefs.includes(query.subjectRef))
      .filter((memory) => !query.type || memory.type === query.type)
      .filter((memory) => !query.createdFrom || memory.createdAt >= query.createdFrom)
      .filter((memory) => !query.createdTo || memory.createdAt <= query.createdTo)
      .filter((memory) => !search || [memory.assertion, ...memory.sourceEvidence.map((evidence) => evidence.excerpt)]
        .some((value) => value.toLocaleLowerCase().includes(search)))
      .slice(0, query.limit ?? 100);
  }

  async listAll(accountId: string): Promise<MemoryRecord[]> {
    return [...this.store.memories.values()]
      .filter((memory) => memory.accountId === accountId)
      .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt) || right.id.localeCompare(left.id))
      .map((memory) => structuredClone(memory));
  }

  async create(memory: MemoryRecord): Promise<MemoryRecord> {
    this.store.memories.set(memory.id, structuredClone(memory));
    return structuredClone(memory);
  }

  async revise(accountId: string, id: string, assertion: string, sourceRef: string): Promise<MemoryRecord | undefined> {
    const current = this.store.memories.get(id);
    if (!current || current.accountId !== accountId) return undefined;
    const [superseded, revised] = reviseMemory(current, assertion, sourceRef);
    this.store.memories.set(superseded.id, superseded);
    this.store.memories.set(revised.id, revised);
    return structuredClone(revised);
  }

  async remove(accountId: string, id: string): Promise<boolean> {
    const current = this.store.memories.get(id);
    if (!current || current.accountId !== accountId) return false;
    if (current.status === "deleted") return true;
    for (const memory of lineage([...this.store.memories.values()].filter((item) => item.accountId === accountId), id)) {
      this.store.memories.set(memory.id, deleteMemory(memory));
    }
    return true;
  }

  async clear(accountId: string): Promise<number> {
    let cleared = 0;
    for (const memory of this.store.memories.values()) {
      if (memory.accountId !== accountId || memory.status === "deleted") continue;
      this.store.memories.set(memory.id, deleteMemory(memory));
      cleared += 1;
    }
    return cleared;
  }

  async removeSubmissionSource(accountId: string, submissionId: string): Promise<number> {
    let changed = 0;
    for (const memory of this.store.memories.values()) {
      if (memory.accountId !== accountId || memory.status === "deleted") continue;
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
      this.store.memories.set(memory.id, updated);
      changed += 1;
    }
    return changed;
  }
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
