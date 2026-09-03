import type { ColumnType } from "kysely";
import type { ActionCard, ActionReceipt, BackgroundJob, ContextExtraction, EpistemicStatus, EvidenceRef, InsightBundle, MemoryRecord, ScreenshotSubmission } from "../domain/model.ts";

type Timestamp = ColumnType<Date, Date | string, Date | string>;
type GeneratedTimestamp = ColumnType<Date, Date | string | undefined, Date | string>;
type Confidence = ColumnType<string, number, number>;
type JsonStringArray = ColumnType<string[], string, string>;
type JsonObject = ColumnType<Record<string, unknown>, string, string>;
type EvidenceJson = ColumnType<ActionCard["evidence"], string, string>;
type FieldConfidenceJson = ColumnType<ActionCard["fieldConfidence"], string, string>;
type StringArrayJson = ColumnType<string[], string, string>;
type ConfirmedSnapshotJson = ColumnType<ActionCard["confirmedSnapshot"] | null, string | null, string | null>;

export interface ActionCardsTable {
  id: string;
  account_id: string;
  submission_id: string;
  action_type: ActionCard["type"];
  version: number;
  fields: JsonObject;
  evidence: EvidenceJson;
  field_confidence: FieldConfidenceJson;
  validation_issues: StringArrayJson;
  target_account_id: string | null;
  status: ActionCard["status"];
  blockers: StringArrayJson;
  confirmed_snapshot: ConfirmedSnapshotJson;
  confirmed_at: Timestamp | null;
  created_at: GeneratedTimestamp;
  updated_at: GeneratedTimestamp;
}

export interface ActionReceiptsTable {
  id: string;
  account_id: string;
  action_card_id: string;
  confirmed_version: number;
  attempt: number;
  status: ActionReceipt["status"];
  provider: ActionReceipt["provider"];
  target_record_id: string | null;
  error_code: string | null;
  error_message: string | null;
  started_at: Timestamp;
  completed_at: Timestamp;
  contact_context: ColumnType<ActionReceipt["contactContext"] | null, string | null, string | null>;
}

export interface ScreenshotSubmissionsTable {
  id: string;
  account_id: string;
  image_object_path: string;
  image_content_type: ScreenshotSubmission["imageContentType"];
  image_byte_size: ColumnType<string, number, number>;
  image_sha256: string;
  supplemental_text: string | null;
  submission_source: ScreenshotSubmission["source"];
  status: ScreenshotSubmission["status"];
  failure_code: string | null;
  processing_started_at: Timestamp | null;
  completed_at: Timestamp | null;
  created_at: Timestamp;
  updated_at: Timestamp;
}

export interface BackgroundJobsTable {
  id: string;
  account_id: string;
  job_type: BackgroundJob["type"];
  aggregate_id: string;
  idempotency_key: string;
  status: BackgroundJob["status"];
  attempt: number;
  max_attempts: number;
  available_at: Timestamp;
  locked_at: Timestamp | null;
  locked_by: string | null;
  last_error_code: string | null;
  created_at: Timestamp;
  updated_at: Timestamp;
}

type JsonArray<T> = ColumnType<T[], string, string>;
type ModelTraceJson = ColumnType<ContextExtraction["modelTrace"], string, string>;

export interface ContextExtractionsTable {
  id: string;
  account_id: string;
  submission_id: string;
  messages: JsonArray<ContextExtraction["messages"][number]>;
  participants: JsonArray<ContextExtraction["participants"][number]>;
  entities: JsonArray<ContextExtraction["entities"][number]>;
  temporal_expressions: JsonArray<ContextExtraction["temporalExpressions"][number]>;
  action_candidates: JsonArray<ContextExtraction["actionCandidates"][number]>;
  evidence_spans: JsonArray<ContextExtraction["evidenceSpans"][number]>;
  warnings: JsonArray<string>;
  model_trace: ModelTraceJson;
  created_at: Timestamp;
}

export interface MemoryRecordsTable {
  id: string;
  account_id: string;
  subject_refs: JsonStringArray;
  memory_type: MemoryRecord["type"];
  assertion: string;
  epistemic_status: EpistemicStatus;
  confidence: Confidence;
  sensitivity: MemoryRecord["sensitivity"];
  source_refs: JsonStringArray;
  source_evidence: JsonArray<EvidenceRef>;
  version: number;
  supersedes_id: string | null;
  status: MemoryRecord["status"];
  created_at: Timestamp;
  updated_at: Timestamp;
}

export interface InsightBundlesTable {
  id: string;
  account_id: string;
  submission_id: string;
  generation_key: string;
  action_receipt_ids: JsonArray<string>;
  items: JsonArray<InsightBundle["items"][number]>;
  model_trace: ColumnType<InsightBundle["modelTrace"], string, string>;
  generated_at: Timestamp;
}

export interface ThreadMindDatabase {
  "threadmind.action_cards": ActionCardsTable;
  "threadmind.action_receipts": ActionReceiptsTable;
  "threadmind.screenshot_submissions": ScreenshotSubmissionsTable;
  "threadmind.background_jobs": BackgroundJobsTable;
  "threadmind.context_extractions": ContextExtractionsTable;
  "threadmind.memory_records": MemoryRecordsTable;
  "threadmind.insight_bundles": InsightBundlesTable;
}
