export type Id = string;
export type EpistemicStatus = "fact" | "inference";
export type ActionType = "create_meeting" | "create_contact" | "update_contact";
export type ActionStatus =
  | "draft"
  | "blocked"
  | "ready"
  | "confirmed"
  | "executing"
  | "succeeded"
  | "failed"
  | "cancelled";
export type SubmissionStatus = "uploaded" | "processing" | "ready" | "failed" | "deleted";
export type JobStatus = "queued" | "running" | "succeeded" | "failed" | "dead";

export interface ScreenshotSubmission {
  id: Id;
  accountId: Id;
  imageObjectPath: string;
  imageContentType: "image/png" | "image/jpeg" | "image/webp";
  imageByteSize: number;
  imageSha256: string;
  supplementalText?: string;
  source: "in_app" | "android_share";
  status: SubmissionStatus;
  failureCode?: string;
  processingStartedAt?: string;
  completedAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface BackgroundJob {
  id: Id;
  accountId: Id;
  type: "analyze_submission" | "delete_submission_artifacts";
  aggregateId: Id;
  idempotencyKey: string;
  status: JobStatus;
  attempt: number;
  maxAttempts: number;
  availableAt: string;
  lockedAt?: string;
  lockedBy?: string;
  lastErrorCode?: string;
  createdAt: string;
  updatedAt: string;
}

export interface ContextExtraction {
  id: Id;
  accountId: Id;
  submissionId: Id;
  messages: Array<{
    id: Id;
    order: number;
    text: string;
    speaker?: string | undefined;
    confidence: number;
    region?: { x: number; y: number; width: number; height: number } | undefined;
  }>;
  participants: Array<{ id: Id; displayName?: string | undefined; evidenceRefs: Id[]; confidence: number }>;
  entities: Array<{ id: Id; type: string; value: string; evidenceRefs: Id[]; confidence: number }>;
  temporalExpressions: Array<{ id: Id; originalText: string; resolvedValue?: string | undefined; timezone?: string | undefined; evidenceRefs: Id[]; confidence: number }>;
  actionCandidates: Array<{
    id: Id;
    type: ActionType;
    fields: Record<string, unknown>;
    evidenceRefs: Id[];
    fieldConfidence: Record<string, number>;
    validationIssues: string[];
    targetAccountId?: string | undefined;
  }>;
  evidenceSpans: Array<{ id: Id; messageId: Id; excerpt: string; confidence: number }>;
  warnings: string[];
  modelTrace: { model: string; promptVersion: string; durationMs?: number | undefined };
  createdAt: string;
}

export interface EvidenceRef {
  sourceId: Id;
  messageId?: Id;
  excerpt: string;
  confidence: number;
}
export interface ActionCard<TFields extends Record<string, unknown> = Record<string, unknown>> {
  id: Id;
  accountId: Id;
  submissionId: Id;
  type: ActionType;
  version: number;
  fields: TFields;
  evidence: EvidenceRef[];
  fieldConfidence: Record<string, number>;
  validationIssues: string[];
  targetAccountId?: string;
  status: ActionStatus;
  blockers: string[];
  confirmedSnapshot?: ConfirmedActionSnapshot<TFields>;
  confirmedAt?: string;
}

export interface ConfirmedActionSnapshot<TFields extends Record<string, unknown> = Record<string, unknown>> {
  actionCardId: Id;
  accountId: Id;
  type: ActionType;
  version: number;
  fields: Readonly<TFields>;
  targetAccountId: string;
  evidence: readonly EvidenceRef[];
  idempotencyKey: string;
}

export interface ActionReceipt {
  id: Id;
  accountId: Id;
  actionCardId: Id;
  confirmedVersion: number;
  attempt: number;
  status: "succeeded" | "failed" | "cancelled";
  provider: "android_calendar" | "android_contacts";
  targetRecordId?: string;
  errorCode?: string;
  errorMessage?: string;
  startedAt: string;
  completedAt: string;
}

export interface MemoryRecord {
  id: Id;
  accountId: Id;
  subjectRefs: Id[];
  type: "event" | "preference" | "relationship" | "commitment" | "profile" | "other";
  assertion: string;
  epistemicStatus: EpistemicStatus;
  confidence: number;
  sensitivity: "normal" | "sensitive" | "highly_sensitive";
  sourceRefs: Id[];
  createdAt: string;
  updatedAt: string;
  version: number;
  supersedesId?: Id;
  status: "active" | "superseded" | "deleted";
}

export interface InsightItem {
  kind: "relationship_context" | "new_development" | "next_step" | "risk";
  title: string;
  explanation: string;
  epistemicStatus: EpistemicStatus;
  confidence: number;
  evidenceRefs: Id[];
  suggestedAction?: string;
  suggestedAt?: string;
}

export interface InsightBundle {
  id: Id;
  accountId: Id;
  submissionId: Id;
  actionReceiptIds: Id[];
  items: InsightItem[];
  generatedAt: string;
  modelTrace: { model: string; promptVersion: string };
}
