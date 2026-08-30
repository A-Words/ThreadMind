import Fastify from "fastify";
import { ZodError } from "zod";
import { authConfigFromEnv, createTokenVerifier, type AuthConfig } from "../account/auth.ts";
import type { ActionRepository } from "../adapters/action-repository.ts";
import { InMemoryActionRepository } from "../adapters/in-memory-action-repository.ts";
import { InMemoryMemoryRepository } from "../adapters/in-memory-memory-repository.ts";
import { InMemoryStore } from "../adapters/in-memory-store.ts";
import type { MemoryRepository } from "../adapters/memory-repository.ts";
import { confirmCard, editCard, evaluateCard } from "../domain/action-card.ts";
import { DomainError } from "../domain/errors.ts";
import type { ActionCard } from "../domain/model.ts";
import { createMemory } from "../domain/memory.ts";
import { cardEditInput, cardInput, cardVersionInput, executionInput, memoryInput, memoryRevisionInput } from "./schemas.ts";

export interface AppOptions {
  allowInsecureAccountHeader?: boolean;
  auth?: AuthConfig;
  actionRepository?: ActionRepository;
  memoryRepository?: MemoryRepository;
}

export function buildApp(store = new InMemoryStore(), options: AppOptions = {}) {
  const app = Fastify({ logger: false });
  const actions = options.actionRepository ?? new InMemoryActionRepository(store);
  const memories = options.memoryRepository ?? new InMemoryMemoryRepository(store);
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
      id: input.cardId, accountId: request.accountId, submissionId: input.submissionId,
      type: input.type, version: 1, fields: input.fields,
      evidence: input.evidence.map(({ messageId, ...item }) => messageId ? { ...item, messageId } : item),
      ...(input.targetAccountId ? { targetAccountId: input.targetAccountId } : {}), status: "draft", blockers: [],
    });
    return reply.code(201).send(await actions.create(draft));
  });
  app.post<{ Params: { id: string } }>("/v1/action-cards/:id/confirm", async (request, reply) => {
    const input = cardVersionInput.parse(request.body);
    const confirmed = await actions.mutate(request.accountId, request.params.id, (card) => {
      if (card.version !== input.expectedVersion) {
        throw new DomainError("card_version_conflict", `Expected version ${input.expectedVersion}, found ${card.version}`);
      }
      return confirmCard(card);
    });
    return confirmed ?? reply.code(404).send({ error: "not_found" });
  });
  app.patch<{ Params: { id: string } }>("/v1/action-cards/:id", async (request, reply) => {
    const input = cardEditInput.parse(request.body);
    const edited = await actions.mutate(request.accountId, request.params.id, (card) => {
      if (card.version !== input.expectedVersion) {
        throw new DomainError("card_version_conflict", `Expected version ${input.expectedVersion}, found ${card.version}`);
      }
      return editCard(card, input.fields);
    });
    return edited ?? reply.code(404).send({ error: "not_found" });
  });
  app.delete<{ Params: { id: string } }>("/v1/action-cards/:id", async (request, reply) => {
    const cancelled = await actions.mutate(request.accountId, request.params.id, (card) => {
      if (card.status === "executing" || card.status === "succeeded") {
        throw new DomainError("card_not_cancellable", "Card can no longer be cancelled");
      }
      return { ...card, status: "cancelled" };
    });
    if (!cancelled) return reply.code(404).send({ error: "not_found" });
    return reply.code(204).send();
  });
  app.post<{ Params: { id: string } }>("/v1/action-cards/:id/receipts", async (request, reply) => {
    const input = executionInput.parse(request.body);
    const execution = input.status === "succeeded"
      ? { status: input.status, targetRecordId: input.targetRecordId } as const
      : {
          status: input.status,
          ...(input.errorCode ? { errorCode: input.errorCode } : {}),
          ...(input.errorMessage ? { errorMessage: input.errorMessage } : {}),
        };
    const result = await actions.recordExecution(request.accountId, request.params.id, input.receiptId, execution);
    if (!result) return reply.code(404).send({ error: "not_found" });
    return reply.code(201).send(result.receipt);
  });
  app.get("/v1/memories", async (request) => ({ items: await memories.listActive(request.accountId) }));
  app.post("/v1/memories", async (request, reply) => {
    const input = memoryInput.parse(request.body);
    const memory = createMemory({ accountId: request.accountId, ...input });
    return reply.code(201).send(await memories.create(memory));
  });
  app.patch<{ Params: { id: string } }>("/v1/memories/:id", async (request, reply) => {
    const input = memoryRevisionInput.parse(request.body);
    const revised = await memories.revise(request.accountId, request.params.id, input.assertion, input.sourceRef);
    if (!revised) return reply.code(404).send({ error: "not_found" });
    return reply.send(revised);
  });
  app.delete<{ Params: { id: string } }>("/v1/memories/:id", async (request, reply) => {
    if (!await memories.remove(request.accountId, request.params.id)) return reply.code(404).send({ error: "not_found" });
    return reply.code(204).send();
  });
  return app;
}

declare module "fastify" {
  interface FastifyRequest { accountId: string }
}
