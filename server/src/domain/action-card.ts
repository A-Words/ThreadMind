import { createHash, randomUUID } from "node:crypto";
import { isDeepStrictEqual } from "node:util";
import { invariant } from "./errors.ts";
import type { ActionCard, ActionReceipt, ConfirmedActionSnapshot, EvidenceRef } from "./model.ts";

const requiredFields = {
  create_meeting: ["title", "startsAt", "endsAt", "timezone", "targetCalendarId"],
  create_contact: ["displayName", "contactMethod", "targetContactAccountId"],
  update_contact: ["targetContactId", "changes"],
} as const;

export function evaluateCard(card: ActionCard): ActionCard {
  const missing = requiredFields[card.type].filter((field) => {
    const value = card.fields[field];
    return value === undefined || value === null || value === "";
  });
  const blockers = [
    ...card.validationIssues.map((issue) => `validation:${issue}`),
    ...missing.map((field) => `missing:${field}`),
  ];
  if (card.evidence.length === 0) blockers.push("missing:evidence");
  if (!card.targetAccountId) blockers.push("missing:targetAccountId");
  return { ...card, blockers, status: blockers.length === 0 ? "ready" : "blocked" };
}

export function editCard(
  card: ActionCard,
  fields: Record<string, unknown>,
  evidence: EvidenceRef[] = card.evidence,
  resolvedValidationIssues: string[] = [],
  targetAccountId: string | undefined = card.targetAccountId,
): ActionCard {
  invariant(!["executing", "succeeded", "cancelled"].includes(card.status), "card_not_editable", "Card can no longer be edited");
  const unknownIssue = resolvedValidationIssues.find((issue) => !card.validationIssues.includes(issue));
  invariant(!unknownIssue, "unknown_validation_issue", "Only current validation issues can be resolved");
  const { confirmedSnapshot: _snapshot, confirmedAt: _confirmedAt, ...editable } = card;
  const fieldConfidence = Object.fromEntries(Object.entries(fields).map(([field, value]) => [
    field,
    Object.hasOwn(card.fields, field) && isDeepStrictEqual(card.fields[field], value)
      ? card.fieldConfidence[field] ?? 1
      : 1,
  ]));
  return evaluateCard({
    ...editable,
    version: card.version + 1,
    fields: structuredClone(fields),
    evidence: structuredClone(evidence),
    fieldConfidence,
    validationIssues: card.validationIssues.filter((issue) => !resolvedValidationIssues.includes(issue)),
    ...(targetAccountId ? { targetAccountId } : {}),
    status: "draft",
  });
}

export function confirmCard(card: ActionCard, now = new Date()): ActionCard {
  if (card.status === "confirmed" && card.confirmedSnapshot?.version === card.version) return structuredClone(card);
  const evaluated = evaluateCard(card);
  invariant(evaluated.status === "ready", "card_not_ready", `Card is blocked: ${evaluated.blockers.join(", ")}`);
  const digest = createHash("sha256")
    .update(`${card.accountId}:${card.id}:${card.version}:${card.targetAccountId}`)
    .digest("hex");
  const snapshot: ConfirmedActionSnapshot = Object.freeze({
    actionCardId: card.id,
    accountId: card.accountId,
    type: card.type,
    version: card.version,
    fields: Object.freeze(structuredClone(card.fields)),
    targetAccountId: card.targetAccountId!,
    evidence: Object.freeze(structuredClone(card.evidence)),
    idempotencyKey: digest,
  });
  return { ...evaluated, status: "confirmed", confirmedSnapshot: snapshot, confirmedAt: now.toISOString() };
}

export function recordExecution(
  card: ActionCard,
  result: { status: "succeeded"; targetRecordId: string } | { status: "failed" | "cancelled"; errorCode?: string; errorMessage?: string },
  previousAttempts: ActionReceipt[],
  now = new Date(),
  receiptId: string = randomUUID(),
): { card: ActionCard; receipt: ActionReceipt } {
  invariant(card.status === "confirmed" || card.status === "failed", "card_not_confirmed", "Only a confirmed snapshot can execute");
  invariant(card.confirmedSnapshot, "snapshot_missing", "Confirmed snapshot is required");
  const startedAt = now.toISOString();
  const provider = card.type === "create_meeting" ? "android_calendar" : "android_contacts";
  const receipt: ActionReceipt = {
    id: receiptId,
    accountId: card.accountId,
    actionCardId: card.id,
    confirmedVersion: card.confirmedSnapshot.version,
    attempt: previousAttempts.length + 1,
    status: result.status,
    provider,
    ...(result.status === "succeeded" ? { targetRecordId: result.targetRecordId } : {}),
    ...(result.status !== "succeeded" && result.errorCode ? { errorCode: result.errorCode } : {}),
    ...(result.status !== "succeeded" && result.errorMessage ? { errorMessage: result.errorMessage } : {}),
    startedAt,
    completedAt: now.toISOString(),
  };
  invariant(receipt.status !== "succeeded" || receipt.targetRecordId, "target_missing", "Successful execution needs a target record id");
  return { card: { ...card, status: result.status }, receipt };
}
