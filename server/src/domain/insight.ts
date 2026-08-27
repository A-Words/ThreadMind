import { randomUUID } from "node:crypto";
import { invariant } from "./errors.js";
import type { ActionReceipt, InsightBundle, InsightItem } from "./model.js";

export function createInsightBundle(input: {
  accountId: string;
  submissionId: string;
  receipts: ActionReceipt[];
  items: InsightItem[];
  modelTrace: InsightBundle["modelTrace"];
}, now = new Date()): InsightBundle {
  const succeeded = input.receipts.filter((receipt) => receipt.status === "succeeded" && receipt.accountId === input.accountId);
  invariant(succeeded.length > 0, "successful_action_required", "Post-action insights require a successful receipt");
  invariant(input.items.every((item) => item.evidenceRefs.length > 0), "insight_evidence_required", "Every insight needs evidence");
  return {
    id: randomUUID(),
    accountId: input.accountId,
    submissionId: input.submissionId,
    actionReceiptIds: succeeded.map((receipt) => receipt.id),
    items: structuredClone(input.items),
    generatedAt: now.toISOString(),
    modelTrace: input.modelTrace,
  };
}
