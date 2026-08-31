import type { InsightBundle } from "../domain/model.ts";
import type { InMemoryStore } from "./in-memory-store.ts";
import type { InsightRepository } from "./insight-repository.ts";

export class InMemoryInsightRepository implements InsightRepository {
  constructor(private readonly store: InMemoryStore) {}

  async findByGenerationKey(accountId: string, generationKey: string): Promise<InsightBundle | undefined> {
    const id = this.store.insightGenerationKeys.get(key(accountId, generationKey));
    const bundle = id ? this.store.insights.get(id) : undefined;
    return bundle ? structuredClone(bundle) : undefined;
  }

  async create(bundle: InsightBundle, generationKey: string): Promise<InsightBundle> {
    const existing = await this.findByGenerationKey(bundle.accountId, generationKey);
    if (existing) return existing;
    this.store.insights.set(bundle.id, structuredClone(bundle));
    this.store.insightGenerationKeys.set(key(bundle.accountId, generationKey), bundle.id);
    return structuredClone(bundle);
  }

  async list(accountId: string, submissionId?: string): Promise<InsightBundle[]> {
    return [...this.store.insights.values()]
      .filter((bundle) => bundle.accountId === accountId && (!submissionId || bundle.submissionId === submissionId))
      .sort((left, right) => right.generatedAt.localeCompare(left.generatedAt) || right.id.localeCompare(left.id))
      .map((bundle) => structuredClone(bundle));
  }
}

function key(accountId: string, generationKey: string) {
  return `${accountId}:${generationKey}`;
}
