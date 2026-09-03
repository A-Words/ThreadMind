import type { MemoryRecord } from "../domain/model.ts";

export interface MemorySearchQuery {
  search?: string;
  subjectRef?: string;
  type?: MemoryRecord["type"];
  createdFrom?: string;
  createdTo?: string;
  limit?: number;
}

export interface MemoryRepository {
  recallActive(accountId: string, query: MemoryRecallQuery): Promise<MemoryRecord[]>;
  listActive(accountId: string, query?: MemorySearchQuery): Promise<MemoryRecord[]>;
  listAll(accountId: string): Promise<MemoryRecord[]>;
  create(memory: MemoryRecord): Promise<MemoryRecord>;
  revise(accountId: string, id: string, assertion: string, sourceRef: string): Promise<MemoryRecord | undefined>;
  remove(accountId: string, id: string): Promise<boolean>;
  clear(accountId: string): Promise<number>;
  removeSubmissionSource(accountId: string, submissionId: string): Promise<number>;
}

export interface MemoryRecallQuery {
  submissionId: string;
  subjectRefs: string[];
}

export const MEMORY_RECALL_LIMIT = 30;

export function isRecallCandidate(memory: MemoryRecord, query: MemoryRecallQuery): boolean {
  return memory.sourceRefs.some((source) => source === query.submissionId || source.startsWith(`${query.submissionId}:`))
    || memory.subjectRefs.some((subject) => query.subjectRefs.includes(subject));
}
