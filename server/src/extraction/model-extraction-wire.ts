import { z } from "zod";

const confidence = z.number().min(0).max(1);
const id = z.string().min(1).max(200);
const evidenceRefs = z.array(id).min(1);

const nullableString = z.string().min(1).nullable();

export const modelExtractionWireSchema = z.object({
  messages: z.array(z.object({
    id,
    order: z.number().int().nonnegative(),
    text: z.string().min(1),
    speaker: nullableString,
    confidence,
    region: z.object({
      x: z.number().min(0).max(1),
      y: z.number().min(0).max(1),
      width: z.number().positive().max(1),
      height: z.number().positive().max(1),
    }).nullable(),
  })),
  participants: z.array(z.object({ id, displayName: nullableString, evidenceRefs, confidence })),
  entities: z.array(z.object({ id, type: z.string().min(1), value: z.string().min(1), evidenceRefs, confidence })),
  temporalExpressions: z.array(z.object({
    id,
    originalText: z.string().min(1),
    resolvedValue: nullableString,
    timezone: nullableString,
    evidenceRefs,
    confidence,
  })),
  actionCandidates: z.array(z.object({
    id,
    type: z.enum(["create_meeting", "create_contact", "update_contact"]),
    fieldValues: z.array(z.object({
      name: z.string().min(1),
      value: z.string(),
      confidence,
    })),
    evidenceRefs,
    validationIssues: z.array(z.string()),
    targetAccountId: nullableString,
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
});

export function normalizeModelExtractionOutput(
  raw: unknown,
  trace: { model: string; promptVersion: string; durationMs: number },
): unknown {
  const output = modelExtractionWireSchema.parse(raw);
  return {
    messages: output.messages.map((message) => ({
      id: message.id,
      order: message.order,
      text: message.text,
      ...(message.speaker ? { speaker: message.speaker } : {}),
      confidence: message.confidence,
      ...(message.region ? { region: message.region } : {}),
    })),
    participants: output.participants.map((participant) => ({
      id: participant.id,
      ...(participant.displayName ? { displayName: participant.displayName } : {}),
      evidenceRefs: participant.evidenceRefs,
      confidence: participant.confidence,
    })),
    entities: output.entities,
    temporalExpressions: output.temporalExpressions.map((expression) => ({
      id: expression.id,
      originalText: expression.originalText,
      ...(expression.resolvedValue ? { resolvedValue: expression.resolvedValue } : {}),
      ...(expression.timezone ? { timezone: expression.timezone } : {}),
      evidenceRefs: expression.evidenceRefs,
      confidence: expression.confidence,
    })),
    actionCandidates: output.actionCandidates.map((candidate) => ({
      id: candidate.id,
      type: candidate.type,
      fields: Object.fromEntries(candidate.fieldValues.map((field) => [field.name, field.value])),
      evidenceRefs: candidate.evidenceRefs,
      fieldConfidence: Object.fromEntries(candidate.fieldValues.map((field) => [field.name, field.confidence])),
      validationIssues: candidate.validationIssues,
      ...(candidate.targetAccountId ? { targetAccountId: candidate.targetAccountId } : {}),
    })),
    memoryCandidates: output.memoryCandidates,
    evidenceSpans: output.evidenceSpans,
    warnings: output.warnings,
    modelTrace: trace,
  };
}
