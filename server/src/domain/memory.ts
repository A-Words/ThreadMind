import { randomUUID } from "node:crypto";
import { invariant } from "./errors.js";
import type { MemoryRecord } from "./model.js";

export function createMemory(input: Omit<MemoryRecord, "id" | "createdAt" | "updatedAt" | "version" | "status">, now = new Date()): MemoryRecord {
  invariant(input.sourceRefs.length > 0, "memory_source_required", "Memory must have a traceable source");
  invariant(input.confidence >= 0 && input.confidence <= 1, "invalid_confidence", "Confidence must be between 0 and 1");
  const timestamp = now.toISOString();
  return { ...structuredClone(input), id: randomUUID(), createdAt: timestamp, updatedAt: timestamp, version: 1, status: "active" };
}
export function reviseMemory(current: MemoryRecord, assertion: string, sourceRef: string, now = new Date()): [MemoryRecord, MemoryRecord] {
  invariant(current.status === "active", "memory_not_active", "Only active memory can be revised");
  const timestamp = now.toISOString();
  const oldVersion: MemoryRecord = { ...current, status: "superseded", updatedAt: timestamp };
  const newVersion: MemoryRecord = {
    ...current,
    id: randomUUID(),
    assertion,
    epistemicStatus: "fact",
    confidence: 1,
    sourceRefs: [...current.sourceRefs, sourceRef],
    createdAt: timestamp,
    updatedAt: timestamp,
    version: current.version + 1,
    supersedesId: current.id,
    status: "active",
  };
  return [oldVersion, newVersion];
}

export function deleteMemory(current: MemoryRecord, now = new Date()): MemoryRecord {
  return { ...current, status: "deleted", updatedAt: now.toISOString() };
}

export function recallable(records: MemoryRecord[], accountId: string): MemoryRecord[] {
  return records.filter((record) => record.accountId === accountId && record.status === "active" && record.sourceRefs.length > 0);
}
