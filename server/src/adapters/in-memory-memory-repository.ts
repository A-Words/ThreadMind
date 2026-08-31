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
    this.store.memories.set(current.id, deleteMemory(current));
    return true;
  }
}
