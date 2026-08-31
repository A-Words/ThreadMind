import type { ActionCard, ActionReceipt, ContextExtraction, InsightBundle, MemoryRecord, ScreenshotSubmission } from "../domain/model.ts";

export type ExportedSubmission = Omit<ScreenshotSubmission, "accountId" | "imageObjectPath" | "imageSha256">;

export interface AccountExport {
  format: "threadmind-export-v1";
  generatedAt: string;
  accountId: string;
  submissions: ExportedSubmission[];
  extractions: ContextExtraction[];
  actionCards: ActionCard[];
  actionReceipts: ActionReceipt[];
  memories: MemoryRecord[];
  insights: InsightBundle[];
}

export interface AccountExportRepository {
  create(accountId: string, now?: Date): Promise<AccountExport>;
}

export function exportedSubmission(submission: ScreenshotSubmission): ExportedSubmission {
  const { accountId: _accountId, imageObjectPath: _path, imageSha256: _sha256, ...exported } = submission;
  return exported;
}
