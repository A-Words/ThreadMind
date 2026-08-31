import type { BackgroundJob, ScreenshotSubmission } from "../domain/model.ts";

export interface SubmissionRepository {
  find(accountId: string, id: string): Promise<ScreenshotSubmission | undefined>;
  createWithJob(submission: ScreenshotSubmission, job: BackgroundJob): Promise<ScreenshotSubmission>;
  remove(accountId: string, id: string): Promise<boolean>;
}
