import type { ActionCard, ActionReceipt, EvidenceRef, InsightBundle, InsightItem, MemoryRecord } from "../domain/model.ts";

export interface InsightGenerationInput {
  card: ActionCard;
  receipt: ActionReceipt & { status: "succeeded"; targetRecordId: string };
  memories: MemoryRecord[];
}

export interface InsightGenerationOutput {
  items: InsightItem[];
  modelTrace: InsightBundle["modelTrace"];
}

export interface InsightGenerator {
  generate(input: InsightGenerationInput): Promise<InsightGenerationOutput>;
}

export class EvidenceBackedInsightGenerator implements InsightGenerator {
  async generate(input: InsightGenerationInput): Promise<InsightGenerationOutput> {
    const receiptEvidence: EvidenceRef = {
      sourceId: `receipt:${input.receipt.id}`,
      excerpt: `${providerLabel(input.receipt.provider)}已返回目标记录 ${input.receipt.targetRecordId}`,
      confidence: 1,
    };
    const items: InsightItem[] = [{
      kind: "new_development",
      title: "已完成确认的行动",
      explanation: completedExplanation(input.card.type),
      epistemicStatus: "fact",
      confidence: 1,
      evidenceRefs: [receiptEvidence.sourceId],
      evidence: [receiptEvidence],
    }];

    const relatedMemory = input.memories.find((memory) => isRelated(memory, input.card));
    if (relatedMemory && relatedMemory.sourceEvidence.length > 0) {
      items.push({
        kind: "relationship_context",
        title: "相关背景",
        explanation: relatedMemory.assertion,
        epistemicStatus: relatedMemory.epistemicStatus,
        confidence: relatedMemory.confidence,
        evidenceRefs: relatedMemory.sourceEvidence.map((evidence) => evidence.sourceId),
        evidence: relatedMemory.sourceEvidence,
      });
    }

    const meetingStart = input.card.type === "create_meeting" ? meetingStartAt(input.card.fields) : undefined;
    if (meetingStart && input.card.evidence.length > 0) {
      items.push({
        kind: "next_step",
        title: "会前准备",
        explanation: "会议已经写入日历；可在开始前复核参与人背景和本次对话中的承诺。",
        epistemicStatus: "inference",
        confidence: 0.75,
        evidenceRefs: unique(input.card.evidence.map((evidence) => evidence.sourceId)),
        evidence: input.card.evidence,
        suggestedAction: "会前复核参与人背景与承诺",
        suggestedAt: meetingStart,
      });
    }

    return {
      items,
      modelTrace: { model: "rules:evidence-v1", promptVersion: "post-action-v1" },
    };
  }
}

function isRelated(memory: MemoryRecord, card: ActionCard): boolean {
  if (memory.sourceRefs.some((source) => source.startsWith(`${card.submissionId}:`))) return true;
  const values = collectStrings(card.fields).map((value) => value.toLocaleLowerCase());
  return memory.subjectRefs.some((subject) => values.includes(subject.toLocaleLowerCase()));
}

function collectStrings(value: unknown): string[] {
  if (typeof value === "string" && value.trim()) return [value.trim()];
  if (Array.isArray(value)) return value.flatMap(collectStrings);
  if (value && typeof value === "object") return Object.values(value).flatMap(collectStrings);
  return [];
}

function meetingStartAt(fields: Record<string, unknown>): string | undefined {
  for (const key of ["startsAt", "startAt", "startTime", "start", "startDateTime"]) {
    const value = fields[key];
    if (typeof value === "string" && value.trim()) return value;
  }
  return undefined;
}

function completedExplanation(type: ActionCard["type"]): string {
  if (type === "create_meeting") return "已按你确认的卡片版本创建日历事件，并保存了系统记录 ID。";
  if (type === "create_contact") return "已按你确认的卡片版本创建联系人，并保存了系统记录 ID。";
  return "已按你确认的字段差异更新联系人，并保存了系统记录 ID。";
}

function providerLabel(provider: ActionReceipt["provider"]): string {
  return provider === "android_calendar" ? "Android Calendar Provider" : "Android Contacts Provider";
}

function unique(values: string[]) {
  return [...new Set(values)];
}
