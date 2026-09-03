import { createHash } from "node:crypto";
import type { InsightRepository } from "../adapters/insight-repository.ts";
import type { MemoryRepository } from "../adapters/memory-repository.ts";
import type { SubmissionRepository } from "../adapters/submission-repository.ts";
import { invariant } from "../domain/errors.ts";
import { createInsightBundle } from "../domain/insight.ts";
import { createMemory } from "../domain/memory.ts";
import type { ActionCard, ActionReceipt, InsightBundle } from "../domain/model.ts";
import type { InsightGenerator } from "./insight-generator.ts";

export class InsightService {
  constructor(
    private readonly repository: InsightRepository,
    private readonly memories: MemoryRepository,
    private readonly generator: InsightGenerator,
    private readonly submissions?: SubmissionRepository,
  ) {}

  async ensureForReceipt(card: ActionCard, receipt: ActionReceipt): Promise<InsightBundle | undefined> {
    if (receipt.status !== "succeeded" || !receipt.targetRecordId) return undefined;
    invariant(card.accountId === receipt.accountId && card.id === receipt.actionCardId && card.version === receipt.confirmedVersion,
      "insight_receipt_mismatch", "Insight context must match the successful action");
    await this.memories.create(actionFactMemory(card, { ...receipt, status: "succeeded", targetRecordId: receipt.targetRecordId }));
    const generationKey = `receipt:${receipt.id}`;
    const existing = await this.repository.findByGenerationKey(receipt.accountId, generationKey);
    if (existing) return existing;
    const extraction = await this.submissions?.findExtraction(receipt.accountId, card.submissionId);
    const subjects = [...new Set([
      ...actionSubjects(card),
      ...(extraction?.participants.flatMap((participant) => participant.displayName?.trim() ? [participant.displayName.trim()] : []) ?? []),
    ])];
    const generated = await this.generator.generate({
      card,
      receipt: { ...receipt, status: "succeeded", targetRecordId: receipt.targetRecordId },
      memories: await this.memories.recallActive(receipt.accountId, { submissionId: card.submissionId, subjectRefs: subjects }),
      ...(extraction ? { extraction } : {}),
    });
    const bundle = createInsightBundle({
      accountId: receipt.accountId,
      submissionId: card.submissionId,
      receipts: [receipt],
      items: generated.items,
      modelTrace: generated.modelTrace,
    });
    return this.repository.create(bundle, generationKey);
  }
}

function actionFactMemory(
  card: ActionCard,
  receipt: ActionReceipt & { status: "succeeded"; targetRecordId: string },
) {
  const sourceId = `${card.submissionId}:receipt:${receipt.id}`;
  const assertion = actionAssertion(card, receipt.targetRecordId);
  return createMemory({
    id: stableUuid(receipt.id, "action-memory"),
    accountId: receipt.accountId,
    subjectRefs: actionSubjects(card),
    type: card.type === "create_meeting" ? "event" : "profile",
    assertion,
    epistemicStatus: "fact",
    confidence: 1,
    sensitivity: "normal",
    sourceRefs: [sourceId],
    sourceEvidence: [{ sourceId, excerpt: assertion, confidence: 1 }],
  });
}

function actionAssertion(card: ActionCard, targetRecordId: string): string {
  if (card.type === "create_meeting") {
    const title = stringField(card, "title") ?? "未命名会议";
    const startsAt = stringField(card, "startsAt");
    return `已创建会议“${title}”${startsAt ? `，开始于 ${startsAt}` : ""}，日历记录 ID 为 ${targetRecordId}。`;
  }
  const name = stringField(card, "displayName") ?? stringField(card, "targetContactId") ?? "联系人";
  return `${card.type === "create_contact" ? "已创建" : "已更新"}联系人“${name}”，通讯录记录 ID 为 ${targetRecordId}。`;
}

function actionSubjects(card: ActionCard): string[] {
  const values = [stringField(card, "displayName"), stringField(card, "contactMethod"), stringField(card, "targetContactId")]
    .filter((value): value is string => value !== undefined);
  const attendees = card.fields.attendees;
  const people = typeof attendees === "string" ? attendees.split(",") : Array.isArray(attendees) ? attendees : [];
  return [...new Set([...values, ...people.filter((value): value is string => typeof value === "string").map((value) => value.trim()).filter(Boolean)])];
}

function stringField(card: ActionCard, key: string): string | undefined {
  const value = card.fields[key];
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function stableUuid(namespace: string, value: string): string {
  const hex = createHash("sha256").update(`${namespace}:${value}`).digest("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-8${hex.slice(17, 20)}-${hex.slice(20, 32)}`;
}
