import { z } from "zod";
import type { EpistemicStatus, EvidenceRef } from "../domain/model.ts";
import type { InsightGenerationInput, InsightGenerator } from "./insight-generator.ts";

export const insightSynthesisSchema = z.strictObject({
  items: z.array(z.strictObject({
    kind: z.enum(["relationship_context", "new_development", "next_step", "risk"]),
    title: z.string().trim().min(1).max(120),
    explanation: z.string().trim().min(1).max(2000),
    epistemicStatus: z.enum(["fact", "inference"]),
    confidence: z.number().min(0).max(1),
    evidenceKeys: z.array(z.string().min(1)).min(1).max(12),
    suggestedAction: z.string().trim().min(1).max(500).nullable(),
    suggestedAt: z.iso.datetime({ offset: true }).nullable(),
  })).min(1).max(6),
});

export interface InsightPremise {
  key: string;
  kind: "receipt" | "confirmed_action" | "current_context" | "memory";
  assertion: string;
  epistemicStatus: EpistemicStatus;
  confidence: number;
  evidence: EvidenceRef[];
}

export interface InsightSynthesisInput {
  action: { type: InsightGenerationInput["card"]["type"]; fields: Record<string, unknown> };
  currentContext: { messages: NonNullable<InsightGenerationInput["extraction"]>["messages"]; warnings: string[] } | null;
  premises: InsightPremise[];
  contactContext: "device_contacts_not_available";
}

export interface InsightSynthesisModel {
  readonly model: string;
  readonly promptVersion: string;
  synthesize(input: InsightSynthesisInput): Promise<unknown>;
}

export class GroundedInsightGenerator implements InsightGenerator {
  constructor(private readonly model: InsightSynthesisModel) {}

  async generate(input: InsightGenerationInput) {
    const context = assembleInsightContext(input);
    const raw = await this.model.synthesize(context);
    const output = insightSynthesisSchema.parse(raw);
    const byKey = new Map(context.premises.map((premise) => [premise.key, premise]));
    const items = output.items.map((item) => {
      const premises = [...new Set(item.evidenceKeys)].map((key) => {
        const premise = byKey.get(key);
        if (!premise) throw new Error("insight_unknown_evidence");
        return premise;
      });
      if (item.epistemicStatus === "fact" && (premises.some((p) => p.epistemicStatus !== "fact") || item.kind === "next_step" || item.suggestedAction)) {
        throw new Error("insight_unsupported_fact");
      }
      if (item.confidence > Math.min(...premises.map((p) => p.confidence))) throw new Error("insight_overconfident");
      if (item.kind === "next_step" && !item.suggestedAction) throw new Error("insight_next_step_required");
      const evidence = premises.flatMap((p) => p.evidence);
      return {
        kind: item.kind, title: item.title, explanation: item.explanation,
        epistemicStatus: item.epistemicStatus, confidence: item.confidence,
        evidenceRefs: [...new Set(evidence.map((e) => e.sourceId))], evidence,
        ...(item.suggestedAction ? { suggestedAction: item.suggestedAction } : {}),
        ...(item.suggestedAt ? { suggestedAt: item.suggestedAt } : {}),
      };
    });
    return { items, modelTrace: { model: this.model.model, promptVersion: this.model.promptVersion } };
  }
}

export function assembleInsightContext(input: InsightGenerationInput): InsightSynthesisInput {
  const { card, receipt, extraction } = input;
  if (card.accountId !== receipt.accountId || card.id !== receipt.actionCardId || card.version !== receipt.confirmedVersion
    || (extraction && (extraction.accountId !== card.accountId || extraction.submissionId !== card.submissionId))) {
    throw new Error("insight_context_mismatch");
  }
  const premises: InsightPremise[] = [];
  function add(kind: InsightPremise["kind"], assertion: string, epistemicStatus: EpistemicStatus, confidence: number, evidence: EvidenceRef[]) {
    const valid = evidence.filter((e) => e.sourceId.trim() && e.excerpt.trim() && Number.isFinite(e.confidence) && e.confidence >= 0 && e.confidence <= 1);
    if (!valid.length) return;
    premises.push({ key: `e${premises.length + 1}`, kind, assertion, epistemicStatus,
      confidence: Math.min(confidence, ...valid.map((e) => e.confidence)), evidence: structuredClone(valid) });
  }
  const completed = `Confirmed ${card.type} succeeded; provider target record: ${receipt.targetRecordId}`;
  add("receipt", completed, "fact", 1, [{ sourceId: `receipt:${receipt.id}`, excerpt: completed, confidence: 1 }]);
  for (const evidence of card.confirmedSnapshot?.evidence ?? card.evidence) {
    add("confirmed_action", evidence.excerpt, "fact", evidence.confidence, [evidence]);
  }
  for (const span of extraction?.evidenceSpans ?? []) {
    add("current_context", span.excerpt, "fact", span.confidence, [{ sourceId: `${card.submissionId}:${span.id}`, messageId: span.messageId, excerpt: span.excerpt, confidence: span.confidence }]);
  }
  for (const memory of input.memories) {
    if (memory.accountId !== card.accountId || memory.status !== "active") continue;
    // Revisions retain old provenance for display, but only the user's correction supports the new assertion.
    const sources = memory.supersedesId ? memory.sourceEvidence.slice(-1) : memory.sourceEvidence;
    add("memory", memory.assertion, memory.epistemicStatus, memory.confidence,
      sources.filter((evidence) => memory.sourceRefs.includes(evidence.sourceId)));
  }
  return {
    action: { type: card.type, fields: structuredClone(card.confirmedSnapshot?.fields ?? card.fields) },
    currentContext: extraction ? { messages: structuredClone(extraction.messages), warnings: [...extraction.warnings] } : null,
    premises, contactContext: "device_contacts_not_available",
  };
}
