import { randomUUID } from "node:crypto";
import { invariant } from "./errors.ts";
import type { MemoryRecord } from "./model.ts";

export function createMemory(
  input: Omit<MemoryRecord, "id" | "createdAt" | "updatedAt" | "version" | "status"> & { id?: string },
  now = new Date(),
): MemoryRecord {
  invariant(input.assertion.trim().length > 0, "memory_assertion_required", "Memory must have a non-empty assertion");
  invariant(input.sourceRefs.length > 0 && input.sourceRefs.every((source) => source.trim().length > 0), "memory_source_required", "Memory must have a traceable source");
  invariant(input.sourceEvidence.length > 0, "memory_evidence_required", "Memory must have displayable source evidence");
  invariant(
    input.sourceEvidence.every((evidence) => input.sourceRefs.includes(evidence.sourceId) && evidence.excerpt.trim().length > 0),
    "memory_evidence_invalid",
    "Memory evidence must reference a source and include an excerpt",
  );
  invariant(input.confidence >= 0 && input.confidence <= 1, "invalid_confidence", "Confidence must be between 0 and 1");
  const timestamp = now.toISOString();
  const { id = randomUUID(), ...content } = structuredClone(input);
  return { ...content, id, createdAt: timestamp, updatedAt: timestamp, version: 1, status: "active" };
}
export function reviseMemory(current: MemoryRecord, assertion: string, sourceRef: string, now = new Date()): [MemoryRecord, MemoryRecord] {
  invariant(current.status === "active", "memory_not_active", "Only active memory can be revised");
  invariant(assertion.trim().length > 0, "memory_assertion_required", "Memory must have a non-empty assertion");
  invariant(sourceRef.trim().length > 0, "memory_source_required", "Memory correction must have a traceable source");
  const timestamp = now.toISOString();
  const oldVersion: MemoryRecord = { ...current, status: "superseded", updatedAt: timestamp };
  const newVersion: MemoryRecord = {
    ...current,
    id: randomUUID(),
    assertion,
    epistemicStatus: "fact",
    confidence: 1,
    sourceRefs: [...current.sourceRefs, sourceRef],
    sourceEvidence: [
      ...current.sourceEvidence,
      { sourceId: sourceRef, excerpt: assertion, confidence: 1 },
    ],
    createdAt: timestamp,
    updatedAt: timestamp,
    version: current.version + 1,
    supersedesId: current.id,
    status: "active",
  };
  return [oldVersion, newVersion];
}

export function deleteMemory(current: MemoryRecord, now = new Date()): MemoryRecord {
  const sourceId = `deleted:${current.id}`;
  return {
    ...current,
    subjectRefs: [],
    assertion: "[deleted]",
    epistemicStatus: "inference",
    confidence: 0,
    sensitivity: "normal",
    sourceRefs: [sourceId],
    sourceEvidence: [{ sourceId, excerpt: "用户已删除此记忆", confidence: 1 }],
    status: "deleted",
    updatedAt: now.toISOString(),
  };
}

export function recallable(records: MemoryRecord[], accountId: string): MemoryRecord[] {
  return records.filter((record) => record.accountId === accountId && record.status === "active" && record.sourceRefs.length > 0);
}
