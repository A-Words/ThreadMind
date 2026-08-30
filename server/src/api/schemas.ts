import { z } from "zod";

export const cardInput = z.object({
  cardId: z.uuid(),
  submissionId: z.uuid(),
  type: z.enum(["create_meeting", "create_contact", "update_contact"]),
  fields: z.record(z.string(), z.unknown()),
  evidence: z.array(z.object({
    sourceId: z.string().min(1),
    messageId: z.string().min(1).optional(),
    excerpt: z.string().min(1),
    confidence: z.number().min(0).max(1),
  })),
  targetAccountId: z.string().min(1).optional(),
});

export const cardEditInput = z.object({
  expectedVersion: z.number().int().positive(),
  fields: cardInput.shape.fields,
});

export const cardVersionInput = z.object({
  expectedVersion: z.number().int().positive(),
});

export const executionInput = z.discriminatedUnion("status", [
  z.object({ receiptId: z.uuid(), status: z.literal("succeeded"), targetRecordId: z.string().min(1) }),
  z.object({ receiptId: z.uuid(), status: z.enum(["failed", "cancelled"]), errorCode: z.string().optional(), errorMessage: z.string().optional() }),
]);

export const memoryInput = z.object({
  subjectRefs: z.array(z.string().min(1)),
  type: z.enum(["event", "preference", "relationship", "commitment", "profile", "other"]),
  assertion: z.string().min(1),
  epistemicStatus: z.enum(["fact", "inference"]),
  confidence: z.number().min(0).max(1),
  sensitivity: z.enum(["normal", "sensitive", "highly_sensitive"]),
  sourceRefs: z.array(z.string().min(1)).min(1),
});

export const memoryRevisionInput = z.object({
  assertion: z.string().min(1),
  sourceRef: z.string().min(1),
});
