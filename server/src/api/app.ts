import Fastify from "fastify";
import multipart from "@fastify/multipart";
import { ZodError } from "zod";
import { authConfigFromEnv, createTokenVerifier, type AuthConfig } from "../account/auth.ts";
import type { ActionRepository } from "../adapters/action-repository.ts";
import { InMemoryActionRepository } from "../adapters/in-memory-action-repository.ts";
import { InMemorySubmissionRepository } from "../adapters/in-memory-submission-repository.ts";
import { InMemoryMemoryRepository } from "../adapters/in-memory-memory-repository.ts";
import { InMemoryStore } from "../adapters/in-memory-store.ts";
import type { MemoryRepository } from "../adapters/memory-repository.ts";
import type { SubmissionRepository } from "../adapters/submission-repository.ts";
import { InMemoryTemporaryImageStorage, type TemporaryImageStorage } from "../adapters/temporary-image-storage.ts";
import { confirmCard, editCard, evaluateCard } from "../domain/action-card.ts";
import { DomainError } from "../domain/errors.ts";
import type { ActionCard } from "../domain/model.ts";
import { createMemory } from "../domain/memory.ts";
import { prepareSubmission, sameSubmissionContent, SCREENSHOT_CONTENT_TYPES } from "../domain/submission.ts";
import { cardEditInput, cardInput, cardVersionInput, executionInput, memoryInput, memoryRevisionInput, submissionFieldsInput } from "./schemas.ts";

export interface AppOptions {
  allowInsecureAccountHeader?: boolean;
  auth?: AuthConfig;
  actionRepository?: ActionRepository;
  memoryRepository?: MemoryRepository;
  submissionRepository?: SubmissionRepository;
  temporaryImageStorage?: TemporaryImageStorage;
}

export function buildApp(store = new InMemoryStore(), options: AppOptions = {}) {
  const app = Fastify({ logger: false });
  const actions = options.actionRepository ?? new InMemoryActionRepository(store);
  const memories = options.memoryRepository ?? new InMemoryMemoryRepository(store);
  const submissions = options.submissionRepository ?? new InMemorySubmissionRepository(store);
  const temporaryImages = options.temporaryImageStorage ?? new InMemoryTemporaryImageStorage();
  app.register(multipart, { limits: { files: 1, fileSize: 15 * 1024 * 1024, fields: 3, parts: 4 } });
  const verifyToken = options.allowInsecureAccountHeader
    ? undefined
    : createTokenVerifier(options.auth ?? authConfigFromEnv(process.env));
  app.setErrorHandler((error, _request, reply) => {
    if (error instanceof DomainError) return reply.code(domainErrorStatus(error.code)).send({ error: error.code, message: error.message });
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
  app.post("/v1/submissions", async (request, reply) => {
    const fields: Record<string, string> = {};
    let image: Buffer | undefined;
    let contentType: (typeof SCREENSHOT_CONTENT_TYPES)[number] | undefined;
    for await (const part of request.parts()) {
      if (part.type === "file") {
        if (part.fieldname !== "image" || image) throw new DomainError("invalid_image_part", "Exactly one image part is required");
        if (!SCREENSHOT_CONTENT_TYPES.includes(part.mimetype as (typeof SCREENSHOT_CONTENT_TYPES)[number])) {
          throw new DomainError("unsupported_image_type", "Only PNG, JPEG and WebP screenshots are supported");
        }
        contentType = part.mimetype as (typeof SCREENSHOT_CONTENT_TYPES)[number];
        image = await part.toBuffer();
      } else if (typeof part.value === "string") {
        fields[part.fieldname] = part.value;
      }
    }
    if (!image || !contentType) throw new DomainError("image_required", "Screenshot image is required");
    const input = submissionFieldsInput.parse(fields);
    const prepared = prepareSubmission({
      id: input.submissionId,
      accountId: request.accountId,
      image,
      contentType,
      source: input.source,
      ...(input.supplementalText ? { supplementalText: input.supplementalText } : {}),
    });
    const existing = await submissions.find(request.accountId, input.submissionId);
    if (existing) {
      if (!sameSubmissionContent(existing, prepared.submission)) throw new DomainError("submission_conflict", "Submission id already has different content");
      return reply.code(202).send(publicSubmission(existing));
    }
    await temporaryImages.putIfAbsent(
      prepared.submission.imageObjectPath,
      image,
      prepared.submission.imageContentType,
      prepared.submission.imageSha256,
    );
    const created = await submissions.createWithJob(prepared.submission, prepared.job);
    return reply.code(202).send(publicSubmission(created));
  });
  app.get<{ Params: { id: string } }>("/v1/submissions/:id", async (request, reply) => {
    const id = submissionFieldsInput.shape.submissionId.parse(request.params.id);
    const submission = await submissions.find(request.accountId, id);
    return submission ? publicSubmission(submission) : reply.code(404).send({ error: "not_found" });
  });
  app.post("/v1/action-cards", async (request, reply) => {
    const input = cardInput.parse(request.body);
    const draft: ActionCard = evaluateCard({
      id: input.cardId, accountId: request.accountId, submissionId: input.submissionId,
      type: input.type, version: 1, fields: input.fields,
      evidence: input.evidence.map(({ messageId, ...item }) => messageId ? { ...item, messageId } : item),
      fieldConfidence: Object.keys(input.fields).reduce<Record<string, number>>((result, field) => {
        result[field] = input.fieldConfidence[field] ?? 1;
        return result;
      }, {}),
      validationIssues: input.validationIssues,
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
      return editCard(card, input.fields, card.evidence, input.resolvedValidationIssues);
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

function publicSubmission(submission: import("../domain/model.ts").ScreenshotSubmission) {
  const { imageObjectPath: _path, imageSha256: _sha256, accountId: _accountId, ...visible } = submission;
  return visible;
}

function domainErrorStatus(code: string): number {
  if (code === "unauthorized") return 401;
  if (code === "image_too_large") return 413;
  if (code === "unsupported_image_type" || code === "image_type_mismatch") return 415;
  if (["image_empty", "image_required", "invalid_image_part", "supplemental_text_too_long"].includes(code)) return 400;
  return 409;
}

declare module "fastify" {
  interface FastifyRequest { accountId: string }
}
