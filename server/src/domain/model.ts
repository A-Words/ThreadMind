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
