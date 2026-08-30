import type { BackgroundJob, ContextExtraction, ScreenshotSubmission } from "../domain/model.ts";
import type { ValidatedAnalysis } from "../extraction/extraction-output.ts";

export interface SubmissionProcessingRepository {
  begin(accountId: string, submissionId: string, now?: Date): Promise<{ submission: ScreenshotSubmission; extraction?: ContextExtraction } | undefined>;
  save(accountId: string, submissionId: string, analysis: ValidatedAnalysis): Promise<void>;
  finalize(accountId: string, submissionId: string, result: "ready" | "failed", failureCode?: string, now?: Date): Promise<ScreenshotSubmission | undefined>;
  enqueueCleanup(accountId: string, submissionId: string, job: BackgroundJob): Promise<void>;
}
