import type { BackgroundJob, ContextExtraction, ScreenshotSubmission } from "../domain/model.ts";

export interface SubmissionRepository {
  find(accountId: string, id: string): Promise<ScreenshotSubmission | undefined>;
  findExtraction(accountId: string, submissionId: string): Promise<ContextExtraction | undefined>;
  createWithJob(submission: ScreenshotSubmission, job: BackgroundJob): Promise<ScreenshotSubmission>;
  remove(accountId: string, id: string): Promise<boolean>;
}
