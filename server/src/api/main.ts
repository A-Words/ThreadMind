import { buildApp } from "./app.ts";
import { KyselyActionRepository } from "../adapters/kysely-action-repository.ts";
import { KyselyMemoryRepository } from "../adapters/kysely-memory-repository.ts";
import { KyselySubmissionRepository } from "../adapters/kysely-submission-repository.ts";
import { createTemporaryImageStorage } from "../adapters/supabase-temporary-image-storage.ts";
import { createDatabase } from "../database/database.ts";

const port = Number(process.env.PORT ?? 3000);
const database = createDatabase(process.env);
const app = buildApp(undefined, {
  actionRepository: new KyselyActionRepository(database),
  memoryRepository: new KyselyMemoryRepository(database),
  submissionRepository: new KyselySubmissionRepository(database),
  temporaryImageStorage: createTemporaryImageStorage(process.env),
});
app.addHook("onClose", async () => database.destroy());
await app.listen({ host: "0.0.0.0", port });
