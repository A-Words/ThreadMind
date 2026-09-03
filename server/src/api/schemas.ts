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
  z.object({ receiptId: z.uuid(), status: z.literal("succeeded"), targetRecordId: z.string().trim().min(1), contactContext: z.object({
    source: z.literal("android_contacts_provider"),
    capturedAt: z.iso.datetime({ offset: true }),
    permissionStatus: z.enum(["granted", "denied", "not_required", "unavailable"]),
    queries: z.array(z.object({ kind: z.enum(["target_record_id", "email", "phone"]), value: z.string().trim().min(1).max(320) }).strict()).max(10),
    records: z.array(z.object({
      providerContactId: z.string().trim().min(1).max(100), displayName: z.string().trim().min(1).max(300).optional(),
      emailAddresses: z.array(z.string().trim().min(1).max(320)).max(3), phoneNumbers: z.array(z.string().trim().min(1).max(100)).max(3),
      organization: z.string().trim().min(1).max(300).optional(), jobTitle: z.string().trim().min(1).max(300).optional(),
      matchBasis: z.enum(["provider_record_id", "exact_email", "exact_phone", "display_name_only"]),
      identityStatus: z.enum(["confirmed_target", "candidate", "ambiguous"]),
    }).strict()).max(10),
  }).strict().superRefine((snapshot, context) => {
    if (snapshot.permissionStatus !== "granted" && snapshot.records.length) context.addIssue({ code: "custom", message: "Contact records require granted permission" });
    snapshot.records.forEach((record, index) => {
      if (record.matchBasis === "display_name_only" && record.identityStatus === "confirmed_target") {
        context.addIssue({ code: "custom", path: ["records", index, "identityStatus"], message: "A display-name match cannot confirm identity" });
      }
      if (record.identityStatus === "confirmed_target" && record.matchBasis !== "provider_record_id") {
        context.addIssue({ code: "custom", path: ["records", index, "identityStatus"], message: "Only the provider target can confirm identity" });
      }
    });
  }).optional() }).strict(),
  z.object({ receiptId: z.uuid(), status: z.enum(["failed", "cancelled"]), errorCode: z.string().trim().min(1).max(100).optional(), errorMessage: z.string().trim().min(1).max(1000).optional() }).strict(),
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
