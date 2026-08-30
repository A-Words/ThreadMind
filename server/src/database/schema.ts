import type { ColumnType } from "kysely";
import type { ActionCard, ActionReceipt, EpistemicStatus, MemoryRecord } from "../domain/model.ts";

type Timestamp = ColumnType<Date, Date | string, Date | string>;
type GeneratedTimestamp = ColumnType<Date, Date | string | undefined, Date | string>;
type Confidence = ColumnType<string, number, number>;
type JsonStringArray = ColumnType<string[], string, string>;
type JsonObject = ColumnType<Record<string, unknown>, string, string>;
type EvidenceJson = ColumnType<ActionCard["evidence"], string, string>;
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
  version: number;
  supersedes_id: string | null;
  status: MemoryRecord["status"];
  created_at: Timestamp;
  updated_at: Timestamp;
}

export interface ThreadMindDatabase {
  "threadmind.action_cards": ActionCardsTable;
  "threadmind.action_receipts": ActionReceiptsTable;
  "threadmind.memory_records": MemoryRecordsTable;
}
