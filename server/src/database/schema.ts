import type { ColumnType } from "kysely";
import type { EpistemicStatus, MemoryRecord } from "../domain/model.ts";

type Timestamp = ColumnType<Date, Date | string, Date | string>;
type Confidence = ColumnType<string, number, number>;
type JsonStringArray = ColumnType<string[], string, string>;

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
  "threadmind.memory_records": MemoryRecordsTable;
}
