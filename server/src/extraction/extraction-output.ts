import { createHash } from "node:crypto";
import { z } from "zod";
import { DomainError } from "../domain/errors.ts";
import { evaluateCard } from "../domain/action-card.ts";
import { createMemory } from "../domain/memory.ts";
import type { ActionCard, ContextExtraction, MemoryRecord } from "../domain/model.ts";

const confidence = z.number().min(0).max(1);
const id = z.string().min(1).max(200);
const evidenceRefs = z.array(id).min(1);

export const extractionOutputSchema = z.object({
  messages: z.array(z.object({
    id,
    order: z.number().int().nonnegative(),
    text: z.string().min(1),
    speaker: z.string().min(1).optional(),
    confidence,
    region: z.object({ x: z.number().min(0).max(1), y: z.number().min(0).max(1), width: z.number().positive().max(1), height: z.number().positive().max(1) }).optional(),
  })),
  participants: z.array(z.object({ id, displayName: z.string().min(1).optional(), evidenceRefs, confidence })),
  entities: z.array(z.object({ id, type: z.string().min(1), value: z.string().min(1), evidenceRefs, confidence })),
  temporalExpressions: z.array(z.object({
    id,
    originalText: z.string().min(1),
    resolvedValue: z.string().min(1).optional(),
    timezone: z.string().min(1).optional(),
    evidenceRefs,
    confidence,
  })),
  actionCandidates: z.array(z.object({
    id,
    type: z.enum(["create_meeting", "create_contact", "update_contact"]),
    fields: z.record(z.string(), z.unknown()),
    evidenceRefs,
    fieldConfidence: z.record(z.string(), confidence),
    validationIssues: z.array(z.string()),
    targetAccountId: z.string().min(1).optional(),
  })),
  memoryCandidates: z.array(z.object({
    id,
    subjectRefs: z.array(id),
    type: z.enum(["event", "preference", "relationship", "commitment", "profile", "other"]),
    assertion: z.string().min(1),
    epistemicStatus: z.enum(["fact", "inference"]),
    confidence,
    sensitivity: z.enum(["normal", "sensitive", "highly_sensitive"]),
    evidenceRefs,
  })),
  evidenceSpans: z.array(z.object({ id, messageId: id, excerpt: z.string().min(1), confidence })),
  warnings: z.array(z.string()),
  modelTrace: z.object({ model: z.string().min(1), promptVersion: z.string().min(1), durationMs: z.number().nonnegative().optional() }),
});

export interface ValidatedAnalysis {
  extraction: ContextExtraction;
  cards: ActionCard[];
  memories: MemoryRecord[];
}

export function validateExtractionOutput(
  raw: unknown,
  context: { accountId: string; submissionId: string },
  now = new Date(),
): ValidatedAnalysis {
  const output = extractionOutputSchema.parse(raw);
  assertUnique(output.messages.map((item) => item.id), "duplicate_message_id");
  assertUnique(output.evidenceSpans.map((item) => item.id), "duplicate_evidence_id");
  const messageById = new Map(output.messages.map((item) => [item.id, item]));
  const evidenceById = new Map(output.evidenceSpans.map((item) => [item.id, item]));
  for (const span of output.evidenceSpans) {
    const message = messageById.get(span.messageId);
    if (!message) throw new DomainError("invalid_evidence_message", `Evidence ${span.id} references a missing message`);
    if (!message.text.includes(span.excerpt)) throw new DomainError("invalid_evidence_excerpt", `Evidence ${span.id} is not present in its message`);
  }
  for (const item of [...output.participants, ...output.entities, ...output.temporalExpressions, ...output.actionCandidates, ...output.memoryCandidates]) {
    for (const evidenceId of item.evidenceRefs) {
      if (!evidenceById.has(evidenceId)) throw new DomainError("invalid_evidence_reference", `Missing evidence ${evidenceId}`);
    }
  }
  const createdAt = now.toISOString();
  const extraction: ContextExtraction = {
    id: stableUuid(context.submissionId, "extraction"),
    accountId: context.accountId,
    submissionId: context.submissionId,
    messages: output.messages,
    participants: output.participants,
    entities: output.entities,
    temporalExpressions: output.temporalExpressions,
    actionCandidates: output.actionCandidates,
    evidenceSpans: output.evidenceSpans,
    warnings: output.warnings,
    modelTrace: output.modelTrace,
    createdAt,
  };
  const cards = output.actionCandidates.map((candidate, index) => evaluateCard({
    id: stableUuid(context.submissionId, `action:${index}:${candidate.id}`),
    accountId: context.accountId,
    submissionId: context.submissionId,
    type: candidate.type,
    version: 1,
    fields: structuredClone(candidate.fields),
    evidence: candidate.evidenceRefs.map((evidenceId) => {
      const evidence = evidenceById.get(evidenceId)!;
      return { sourceId: context.submissionId, messageId: evidence.messageId, excerpt: evidence.excerpt, confidence: evidence.confidence };
    }),
    fieldConfidence: structuredClone(candidate.fieldConfidence),
    validationIssues: [...candidate.validationIssues],
    ...(candidate.targetAccountId ? { targetAccountId: candidate.targetAccountId } : {}),
    status: "draft",
    blockers: [],
  }));
  const memories = output.memoryCandidates.map((candidate, index) => {
    const sourceEvidence = candidate.evidenceRefs.map((evidenceId) => {
      const evidence = evidenceById.get(evidenceId)!;
      return {
        sourceId: `${context.submissionId}:${evidenceId}`,
        messageId: evidence.messageId,
        excerpt: evidence.excerpt,
        confidence: evidence.confidence,
      };
    });
    return createMemory({
      id: stableUuid(context.submissionId, `memory:${index}:${candidate.id}`),
      accountId: context.accountId,
      subjectRefs: candidate.subjectRefs,
      type: candidate.type,
      assertion: candidate.assertion,
      epistemicStatus: candidate.epistemicStatus,
      confidence: candidate.confidence,
      sensitivity: candidate.sensitivity,
      sourceRefs: sourceEvidence.map((evidence) => evidence.sourceId),
      sourceEvidence,
    }, now);
  });
  return { extraction, cards, memories };
}

function assertUnique(values: string[], code: string): void {
  if (new Set(values).size !== values.length) throw new DomainError(code, "Model output identifiers must be unique");
}

function stableUuid(namespace: string, value: string): string {
  const hex = createHash("sha256").update(`${namespace}:${value}`).digest("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-8${hex.slice(17, 20)}-${hex.slice(20, 32)}`;
}
