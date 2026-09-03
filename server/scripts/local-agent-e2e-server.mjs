import { buildApp } from "../dist/src/api/app.js";
import { InMemoryStore } from "../dist/src/adapters/in-memory-store.js";
import { InMemoryTemporaryImageStorage } from "../dist/src/adapters/temporary-image-storage.js";
import { InMemoryWorkerQueueRepository } from "../dist/src/adapters/in-memory-worker-queue-repository.js";
import { InMemorySubmissionProcessingRepository } from "../dist/src/adapters/in-memory-submission-processing-repository.js";
import { createVisionExtractionModel } from "../dist/src/extraction/openai-responses-vision-model.js";
import { createInsightGenerator } from "../dist/src/insight/openai-responses-insight-model.js";
import { SubmissionWorker } from "../dist/src/worker/submission-worker.js";

if (!process.env.OPENAI_API_KEY || !process.env.THREADMIND_VISION_MODEL || !process.env.THREADMIND_INSIGHT_MODEL) {
  throw new Error("Real Agent E2E requires explicit vision and insight model configuration");
}
const store = new InMemoryStore();
const images = new InMemoryTemporaryImageStorage();
const worker = new SubmissionWorker("local-real-e2e", new InMemoryWorkerQueueRepository(store),
  new InMemorySubmissionProcessingRepository(store), images, createVisionExtractionModel(process.env));
const app = buildApp(store, { allowInsecureAccountHeader: true, temporaryImageStorage: images, insightGenerator: createInsightGenerator(process.env) });
let running = false;
const timer = setInterval(async () => {
  if (running) return;
  running = true;
  try { while (await worker.runOne()) { /* drain */ } } finally { running = false; }
}, 250);
app.addHook("onClose", async () => clearInterval(timer));
await app.listen({ host: "0.0.0.0", port: 3000 });
console.log("threadmind_local_real_e2e_ready");
