import type { BackgroundJob, ContextExtraction, ScreenshotSubmission } from "../domain/model.ts";
import type { SubmissionHistoryPage, SubmissionHistoryQuery } from "../domain/submission-history.ts";

export interface SubmissionRepository {
  list(accountId: string, query: SubmissionHistoryQuery): Promise<SubmissionHistoryPage>;
  find(accountId: string, id: string): Promise<ScreenshotSubmission | undefined>;
  findExtraction(accountId: string, submissionId: string): Promise<ContextExtraction | undefined>;
  createWithJob(submission: ScreenshotSubmission, job: BackgroundJob): Promise<ScreenshotSubmission>;
  remove(accountId: string, id: string): Promise<boolean>;
}
