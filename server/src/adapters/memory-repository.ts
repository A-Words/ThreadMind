import type { MemoryRecord } from "../domain/model.ts";

export interface MemoryRepository {
  listActive(accountId: string): Promise<MemoryRecord[]>;
  create(memory: MemoryRecord): Promise<MemoryRecord>;
  revise(accountId: string, id: string, assertion: string, sourceRef: string): Promise<MemoryRecord | undefined>;
  remove(accountId: string, id: string): Promise<boolean>;
}
