import type { Insertable, Kysely, Selectable } from "kysely";
import { DomainError } from "../domain/errors.ts";
import type { ActionCard, BackgroundJob, ContextExtraction, MemoryRecord, ScreenshotSubmission } from "../domain/model.ts";
import type { ValidatedAnalysis } from "../extraction/extraction-output.ts";
import { withAccount } from "../database/account-transaction.ts";
import type {
  ActionCardsTable,
  BackgroundJobsTable,
  ContextExtractionsTable,
  MemoryRecordsTable,
  ScreenshotSubmissionsTable,
  ThreadMindDatabase,
} from "../database/schema.ts";
import type { SubmissionProcessingRepository } from "./submission-processing-repository.ts";

export class KyselySubmissionProcessingRepository implements SubmissionProcessingRepository {
  constructor(private readonly database: Kysely<ThreadMindDatabase>) {}

  async begin(accountId: string, submissionId: string, now = new Date()) {
    return withAccount(this.database, accountId, async (transaction) => {
      const current = await transaction
        .selectFrom("threadmind.screenshot_submissions")
        .selectAll()
        .where("id", "=", submissionId)
        .where("status", "!=", "deleted")
        .forUpdate()
        .executeTakeFirst();
      if (!current) return undefined;
      let submission = toSubmission(current);
      if (submission.status === "uploaded") {
        const updated = await transaction
          .updateTable("threadmind.screenshot_submissions")
          .set({ status: "processing", processing_started_at: now, updated_at: now })
          .where("id", "=", submissionId)
          .returningAll()
          .executeTakeFirstOrThrow();
        submission = toSubmission(updated);
      }
      const extractionRow = await transaction
        .selectFrom("threadmind.context_extractions")
        .selectAll()
        .where("submission_id", "=", submissionId)
        .executeTakeFirst();
      return { submission, ...(extractionRow ? { extraction: toExtraction(extractionRow) } : {}) };
    });
  }

  async save(accountId: string, submissionId: string, analysis: ValidatedAnalysis): Promise<void> {
    if (analysis.extraction.accountId !== accountId || analysis.extraction.submissionId !== submissionId) {
      throw new DomainError("analysis_scope_mismatch", "Analysis does not belong to the claimed submission");
    }
    await withAccount(this.database, accountId, async (transaction) => {
      const submission = await transaction
        .selectFrom("threadmind.screenshot_submissions")
        .select(["id", "status"])
        .where("id", "=", submissionId)
        .forUpdate()
        .executeTakeFirst();
      if (!submission || submission.status !== "processing") throw new DomainError("submission_not_processing", "Submission is not processing");
      await transaction
        .insertInto("threadmind.context_extractions")
        .values(toExtractionRow(analysis.extraction))
        .onConflict((conflict) => conflict.columns(["account_id", "submission_id"]).doNothing())
        .execute();
      if (analysis.cards.length > 0) {
        await transaction
          .insertInto("threadmind.action_cards")
          .values(analysis.cards.map(toActionCardRow))
          .onConflict((conflict) => conflict.column("id").doNothing())
          .execute();
      }
      if (analysis.memories.length > 0) {
        await transaction
          .insertInto("threadmind.memory_records")
          .values(analysis.memories.map(toMemoryRow))
          .onConflict((conflict) => conflict.column("id").doNothing())
          .execute();
      }
    });
  }

  async finalize(accountId: string, submissionId: string, result: "ready" | "failed", failureCode?: string, now = new Date()) {
    return withAccount(this.database, accountId, async (transaction) => {
      const row = await transaction
        .updateTable("threadmind.screenshot_submissions")
        .set({
          status: result,
          failure_code: result === "failed" ? failureCode ?? "analysis_failed" : null,
          completed_at: now,
          updated_at: now,
        })
        .where("id", "=", submissionId)
        .where("status", "in", ["processing", result])
        .returningAll()
        .executeTakeFirst();
      return row ? toSubmission(row) : undefined;
    });
  }

  async enqueueCleanup(accountId: string, submissionId: string, job: BackgroundJob): Promise<void> {
    await withAccount(this.database, accountId, async (transaction) => {
      await transaction
        .insertInto("threadmind.background_jobs")
        .values(toJobRow(job))
        .onConflict((conflict) => conflict.columns(["account_id", "job_type", "idempotency_key"]).doNothing())
        .execute();
    });
  }
}

function toExtractionRow(extraction: ContextExtraction): Insertable<ContextExtractionsTable> {
  return {
    id: extraction.id,
    account_id: extraction.accountId,
    submission_id: extraction.submissionId,
    messages: JSON.stringify(extraction.messages),
    participants: JSON.stringify(extraction.participants),
    entities: JSON.stringify(extraction.entities),
    temporal_expressions: JSON.stringify(extraction.temporalExpressions),
    action_candidates: JSON.stringify(extraction.actionCandidates),
    evidence_spans: JSON.stringify(extraction.evidenceSpans),
    warnings: JSON.stringify(extraction.warnings),
    model_trace: JSON.stringify(extraction.modelTrace),
    created_at: extraction.createdAt,
  };
}

function toActionCardRow(card: ActionCard): Insertable<ActionCardsTable> {
  return {
    id: card.id,
    account_id: card.accountId,
    submission_id: card.submissionId,
    action_type: card.type,
    version: card.version,
    fields: JSON.stringify(card.fields),
    evidence: JSON.stringify(card.evidence),
    target_account_id: card.targetAccountId ?? null,
    status: card.status,
    blockers: JSON.stringify(card.blockers),
    confirmed_snapshot: null,
    confirmed_at: null,
  };
}

function toMemoryRow(memory: MemoryRecord): Insertable<MemoryRecordsTable> {
  return {
    id: memory.id,
    account_id: memory.accountId,
    subject_refs: JSON.stringify(memory.subjectRefs),
    memory_type: memory.type,
    assertion: memory.assertion,
    epistemic_status: memory.epistemicStatus,
    confidence: memory.confidence,
    sensitivity: memory.sensitivity,
    source_refs: JSON.stringify(memory.sourceRefs),
    version: memory.version,
    supersedes_id: memory.supersedesId ?? null,
    status: memory.status,
    created_at: memory.createdAt,
    updated_at: memory.updatedAt,
  };
}

function toJobRow(job: BackgroundJob): Insertable<BackgroundJobsTable> {
  return {
    id: job.id,
    account_id: job.accountId,
    job_type: job.type,
    aggregate_id: job.aggregateId,
    idempotency_key: job.idempotencyKey,
    status: job.status,
    attempt: job.attempt,
    max_attempts: job.maxAttempts,
    available_at: job.availableAt,
    locked_at: job.lockedAt ?? null,
    locked_by: job.lockedBy ?? null,
    last_error_code: job.lastErrorCode ?? null,
    created_at: job.createdAt,
    updated_at: job.updatedAt,
  };
}

function toSubmission(row: Selectable<ScreenshotSubmissionsTable>): ScreenshotSubmission {
  return {
    id: row.id,
    accountId: row.account_id,
    imageObjectPath: row.image_object_path,
    imageContentType: row.image_content_type,
    imageByteSize: Number(row.image_byte_size),
    imageSha256: row.image_sha256,
    ...(row.supplemental_text ? { supplementalText: row.supplemental_text } : {}),
    source: row.submission_source,
    status: row.status,
    ...(row.failure_code ? { failureCode: row.failure_code } : {}),
    ...(row.processing_started_at ? { processingStartedAt: new Date(row.processing_started_at).toISOString() } : {}),
    ...(row.completed_at ? { completedAt: new Date(row.completed_at).toISOString() } : {}),
    createdAt: new Date(row.created_at).toISOString(),
    updatedAt: new Date(row.updated_at).toISOString(),
  };
}

function toExtraction(row: Selectable<ContextExtractionsTable>): ContextExtraction {
  return {
    id: row.id,
    accountId: row.account_id,
    submissionId: row.submission_id,
    messages: row.messages,
    participants: row.participants,
    entities: row.entities,
    temporalExpressions: row.temporal_expressions,
    actionCandidates: row.action_candidates,
    evidenceSpans: row.evidence_spans,
    warnings: row.warnings,
    modelTrace: row.model_trace,
    createdAt: new Date(row.created_at).toISOString(),
  };
}
