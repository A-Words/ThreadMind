import { buildApp } from "./app.ts";
import { createAuthAdmin } from "../account/auth-admin.ts";
import { KyselySensitiveSessionVerifier } from "../account/sensitive-session-verifier.ts";
import { KyselyActionRepository } from "../adapters/kysely-action-repository.ts";
import { KyselyAccountExportRepository } from "../adapters/kysely-account-export-repository.ts";
import { KyselyMemoryRepository } from "../adapters/kysely-memory-repository.ts";
import { KyselyInsightRepository } from "../adapters/kysely-insight-repository.ts";
import { KyselySubmissionRepository } from "../adapters/kysely-submission-repository.ts";
import { createTemporaryImageStorage } from "../adapters/supabase-temporary-image-storage.ts";
import { createDatabase } from "../database/database.ts";
import { createInsightGenerator } from "../insight/openai-responses-insight-model.ts";

const port = Number(process.env.PORT ?? 3000);
const database = createDatabase(process.env);
const app = buildApp(undefined, {
  insightGenerator: createInsightGenerator(process.env),
  authAdmin: createAuthAdmin(process.env),
  sensitiveSessionVerifier: new KyselySensitiveSessionVerifier(database),
  actionRepository: new KyselyActionRepository(database),
  accountExportRepository: new KyselyAccountExportRepository(database),
  memoryRepository: new KyselyMemoryRepository(database),
  insightRepository: new KyselyInsightRepository(database),
  submissionRepository: new KyselySubmissionRepository(database),
  temporaryImageStorage: createTemporaryImageStorage(process.env),
});
app.addHook("onClose", async () => database.destroy());
await app.listen({ host: "0.0.0.0", port });
