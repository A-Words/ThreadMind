import type { InsightBundle } from "../domain/model.ts";

export interface InsightRepository {
  findByGenerationKey(accountId: string, generationKey: string): Promise<InsightBundle | undefined>;
  create(bundle: InsightBundle, generationKey: string): Promise<InsightBundle>;
  list(accountId: string, submissionId?: string): Promise<InsightBundle[]>;
}
