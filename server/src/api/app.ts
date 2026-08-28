import { randomUUID } from "node:crypto";
import Fastify from "fastify";
import { ZodError } from "zod";
import { authConfigFromEnv, createTokenVerifier, type AuthConfig } from "../account/auth.ts";
import { InMemoryStore } from "../adapters/in-memory-store.ts";
import { confirmCard, editCard, evaluateCard, recordExecution } from "../domain/action-card.ts";
import { DomainError } from "../domain/errors.ts";
import type { ActionCard } from "../domain/model.ts";
import { createMemory, deleteMemory, reviseMemory } from "../domain/memory.ts";
import { cardInput, executionInput, memoryInput, memoryRevisionInput } from "./schemas.ts";

export interface AppOptions {
  allowInsecureAccountHeader?: boolean;
  auth?: AuthConfig;
}

export function buildApp(store = new InMemoryStore(), options: AppOptions = {}) {
  const app = Fastify({ logger: false });
  const verifyToken = options.allowInsecureAccountHeader
    ? undefined
    : createTokenVerifier(options.auth ?? authConfigFromEnv(process.env));
  app.setErrorHandler((error, _request, reply) => {
    if (error instanceof DomainError) return reply.code(error.code === "unauthorized" ? 401 : 409).send({ error: error.code, message: error.message });
    if (error instanceof ZodError) return reply.code(400).send({ error: "invalid_request", message: error.message });
    return reply.send(error);
  });
  app.addHook("preHandler", async (request, reply) => {
    if (request.url === "/health") return;
    if (options.allowInsecureAccountHeader) {
      const accountId = request.headers["x-account-id"];
      if (typeof accountId !== "string" || accountId.length === 0) return reply.code(401).send({ error: "unauthorized" });
      request.accountId = accountId;
      return;
    }
    request.accountId = await verifyToken!(request.headers.authorization);
  });
  app.get("/health", { config: { public: true } }, async () => ({ status: "ok" }));
  app.post("/v1/action-cards", async (request, reply) => {
    const input = cardInput.parse(request.body);
    const draft: ActionCard = evaluateCard({
      id: randomUUID(), accountId: request.accountId, submissionId: input.submissionId,
      type: input.type, version: 1, fields: input.fields,
      evidence: input.evidence.map(({ messageId, ...item }) => messageId ? { ...item, messageId } : item),
      ...(input.targetAccountId ? { targetAccountId: input.targetAccountId } : {}), status: "draft", blockers: [],
    });
    store.cards.set(draft.id, draft);
    return reply.code(201).send(draft);
  });
  app.post<{ Params: { id: string } }>("/v1/action-cards/:id/confirm", async (request, reply) => {
    const card = store.card(request.accountId, request.params.id);
    if (!card) return reply.code(404).send({ error: "not_found" });
    const confirmed = confirmCard(card);
    store.cards.set(confirmed.id, confirmed);
    return confirmed;
  });
  app.patch<{ Params: { id: string } }>("/v1/action-cards/:id", async (request, reply) => {
    const card = store.card(request.accountId, request.params.id);
    if (!card) return reply.code(404).send({ error: "not_found" });
    const fields = cardInput.shape.fields.parse(request.body);
    const edited = editCard(card, fields);
    store.cards.set(edited.id, edited);
    return edited;
  });
  app.delete<{ Params: { id: string } }>("/v1/action-cards/:id", async (request, reply) => {
    const card = store.card(request.accountId, request.params.id);
    if (!card) return reply.code(404).send({ error: "not_found" });
    if (card.status === "executing" || card.status === "succeeded") return reply.code(409).send({ error: "card_not_cancellable" });
    store.cards.set(card.id, { ...card, status: "cancelled" });
    return reply.code(204).send();
  });
  app.post<{ Params: { id: string } }>("/v1/action-cards/:id/receipts", async (request, reply) => {
    const card = store.card(request.accountId, request.params.id);
    if (!card) return reply.code(404).send({ error: "not_found" });
    const input = executionInput.parse(request.body);
    const prior = store.receipts.filter((receipt) => receipt.actionCardId === card.id);
    const execution = input.status === "succeeded"
      ? input
      : {
          status: input.status,
          ...(input.errorCode ? { errorCode: input.errorCode } : {}),
          ...(input.errorMessage ? { errorMessage: input.errorMessage } : {}),
        };
    const result = recordExecution(card, execution, prior);
    store.cards.set(card.id, result.card);
    store.receipts.push(result.receipt);
    return reply.code(201).send(result.receipt);
  });
  app.get("/v1/memories", async (request) => ({ items: store.activeMemories(request.accountId) }));
  app.post("/v1/memories", async (request, reply) => {
    const input = memoryInput.parse(request.body);
    const memory = createMemory({ accountId: request.accountId, ...input });
    store.memories.set(memory.id, memory);
    return reply.code(201).send(memory);
  });
  app.patch<{ Params: { id: string } }>("/v1/memories/:id", async (request, reply) => {
    const current = store.memories.get(request.params.id);
    if (!current || current.accountId !== request.accountId) return reply.code(404).send({ error: "not_found" });
    const input = memoryRevisionInput.parse(request.body);
    const [superseded, revised] = reviseMemory(current, input.assertion, input.sourceRef);
    store.memories.set(superseded.id, superseded);
    store.memories.set(revised.id, revised);
    return reply.send(revised);
  });
  app.delete<{ Params: { id: string } }>("/v1/memories/:id", async (request, reply) => {
    const current = store.memories.get(request.params.id);
    if (!current || current.accountId !== request.accountId) return reply.code(404).send({ error: "not_found" });
    store.memories.set(current.id, deleteMemory(current));
    return reply.code(204).send();
  });
  return app;
}

declare module "fastify" {
  interface FastifyRequest { accountId: string }
}
