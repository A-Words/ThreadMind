import { z } from "zod";

const evidenceInput = z.object({
  sourceId: z.string().trim().min(1),
  messageId: z.string().trim().min(1).optional(),
  excerpt: z.string().trim().min(1),
  confidence: z.number().min(0).max(1),
});

export const submissionFieldsInput = z.object({
  submissionId: z.uuid(),
  source: z.enum(["in_app", "android_share"]),
  supplementalText: z.string().max(4000).optional(),
});

export const cardInput = z.object({
  cardId: z.uuid(),
  submissionId: z.uuid(),
  type: z.enum(["create_meeting", "create_contact", "update_contact"]),
  fields: z.record(z.string(), z.unknown()),
  fieldConfidence: z.record(z.string(), z.number().min(0).max(1)).default({}),
  validationIssues: z.array(z.string().min(1)).default([]),
  evidence: z.array(evidenceInput),
  targetAccountId: z.string().trim().min(1).optional(),
});

export const cardEditInput = z.object({
  expectedVersion: z.number().int().positive(),
  type: cardInput.shape.type.optional(),
  fields: cardInput.shape.fields,
  targetAccountId: z.string().trim().min(1).optional(),
  resolvedValidationIssues: z.array(z.string().min(1)).default([]),
});

export const cardVersionInput = z.object({
  expectedVersion: z.number().int().positive(),
});

export const executionInput = z.discriminatedUnion("status", [
  z.object({ receiptId: z.uuid(), status: z.literal("succeeded"), targetRecordId: z.string().trim().min(1) }),
  z.object({ receiptId: z.uuid(), status: z.enum(["failed", "cancelled"]), errorCode: z.string().trim().min(1).max(100).optional(), errorMessage: z.string().trim().min(1).max(1000).optional() }),
]);

export const memoryInput = z.object({
  subjectRefs: z.array(z.string().min(1)),
  type: z.enum(["event", "preference", "relationship", "commitment", "profile", "other"]),
  assertion: z.string().trim().min(1),
  epistemicStatus: z.enum(["fact", "inference"]),
  confidence: z.number().min(0).max(1),
  sensitivity: z.enum(["normal", "sensitive", "highly_sensitive"]),
  sourceRefs: z.array(z.string().trim().min(1)).min(1),
  sourceEvidence: z.array(evidenceInput).min(1),
});

export const memorySearchInput = z.object({
  q: z.string().trim().min(1).max(200).optional(),
  subjectRef: z.string().trim().min(1).max(200).optional(),
  type: memoryInput.shape.type.optional(),
  from: z.iso.datetime({ offset: true }).optional(),
  to: z.iso.datetime({ offset: true }).optional(),
  limit: z.coerce.number().int().min(1).max(100).default(100),
}).refine((value) => !value.from || !value.to || value.from <= value.to, {
  message: "from must not be after to",
});

export const insightSearchInput = z.object({
  submissionId: z.uuid().optional(),
});

export const memoryRevisionInput = z.object({
  assertion: z.string().trim().min(1),
  sourceRef: z.string().trim().min(1),
});
