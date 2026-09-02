import { z } from "zod";
import type { ActionStatus, ScreenshotSubmission } from "./model.ts";

export const attentionActionStatuses = ["draft", "blocked", "ready", "confirmed", "executing"] as const;
export const actionStatuses: ActionStatus[] = [...attentionActionStatuses, "succeeded", "failed", "cancelled"];
export interface SubmissionSummary {
  id: string;
  createdAt: string;
  updatedAt: string;
  source: ScreenshotSubmission["source"];
  status: ScreenshotSubmission["status"];
  actionCounts: Record<string, number>;
}
export interface SubmissionHistoryQuery {
  view: "all" | "attention";
  limit: number;
  cursor?: { createdAt: string; id: string };
}
export interface SubmissionHistoryPage { items: SubmissionSummary[]; nextCursor: string | null }

const cursorSchema = z.object({ createdAt: z.iso.datetime(), id: z.uuid() });
export const submissionHistoryInput = z.object({
  view: z.enum(["all", "attention"]).default("all"),
  limit: z.coerce.number().int().min(1).max(50).default(20),
  cursor: z.string().max(512).regex(/^[A-Za-z0-9_-]+$/).transform((value, context) => {
    try { return cursorSchema.parse(JSON.parse(Buffer.from(value, "base64url").toString("utf8"))); }
    catch { context.addIssue({ code: "custom", message: "Invalid history cursor" }); return z.NEVER; }
  }).optional(),
});

export function historyPage(rows: SubmissionSummary[], limit: number): SubmissionHistoryPage {
  const items = rows.slice(0, limit);
  const last = items.at(-1);
  return { items, nextCursor: rows.length > limit && last
    ? Buffer.from(JSON.stringify({ createdAt: last.createdAt, id: last.id })).toString("base64url") : null };
}

export function emptyActionCounts(): Record<string, number> {
  return Object.fromEntries(actionStatuses.map((status) => [status, 0]));
}
