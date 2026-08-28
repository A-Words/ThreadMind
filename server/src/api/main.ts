import { buildApp } from "./app.ts";
import { KyselyMemoryRepository } from "../adapters/kysely-memory-repository.ts";
import { createDatabase } from "../database/database.ts";

const port = Number(process.env.PORT ?? 3000);
const database = createDatabase(process.env);
const app = buildApp(undefined, { memoryRepository: new KyselyMemoryRepository(database) });
app.addHook("onClose", async () => database.destroy());
await app.listen({ host: "0.0.0.0", port });
