import { createHash } from "node:crypto";
import Fastify from "fastify";
import multipart from "@fastify/multipart";
import { ZodError } from "zod";
import { authConfigFromEnv, createTokenVerifier, type AuthConfig } from "../account/auth.ts";
import { InMemoryAuthAdmin, type AuthAdmin } from "../account/auth-admin.ts";
import { InMemorySensitiveSessionVerifier, type SensitiveSessionVerifier } from "../account/sensitive-session-verifier.ts";
import type { ActionRepository } from "../adapters/action-repository.ts";
import type { AccountExportRepository } from "../adapters/account-export-repository.ts";
import { InMemoryAccountExportRepository } from "../adapters/in-memory-account-export-repository.ts";
import { InMemoryActionRepository } from "../adapters/in-memory-action-repository.ts";
import { InMemorySubmissionRepository } from "../adapters/in-memory-submission-repository.ts";
import { InMemoryInsightRepository } from "../adapters/in-memory-insight-repository.ts";
import { InMemoryMemoryRepository } from "../adapters/in-memory-memory-repository.ts";
import { InMemoryStore } from "../adapters/in-memory-store.ts";
import type { MemoryRepository } from "../adapters/memory-repository.ts";
import type { InsightRepository } from "../adapters/insight-repository.ts";
import type { SubmissionRepository } from "../adapters/submission-repository.ts";
import { InMemoryTemporaryImageStorage, type TemporaryImageStorage } from "../adapters/temporary-image-storage.ts";
import { confirmCard, editCard, evaluateCard } from "../domain/action-card.ts";
import { DomainError } from "../domain/errors.ts";
import type { ActionCard } from "../domain/model.ts";
import { createMemory } from "../domain/memory.ts";
import { prepareSubmission, sameSubmissionContent, SCREENSHOT_CONTENT_TYPES } from "../domain/submission.ts";
import { EvidenceBackedInsightGenerator, type InsightGenerator } from "../insight/insight-generator.ts";
import { InsightService } from "../insight/insight-service.ts";
import { cardEditInput, cardInput, cardVersionInput, executionInput, insightSearchInput, memoryInput, memoryRevisionInput, memorySearchInput, submissionFieldsInput } from "./schemas.ts";

export interface AppOptions {
  allowInsecureAccountHeader?: boolean;
  auth?: AuthConfig;
  authAdmin?: AuthAdmin;
  sensitiveSessionVerifier?: SensitiveSessionVerifier;
  actionRepository?: ActionRepository;
  accountExportRepository?: AccountExportRepository;
  memoryRepository?: MemoryRepository;
  insightRepository?: InsightRepository;
  insightGenerator?: InsightGenerator;
  submissionRepository?: SubmissionRepository;
  temporaryImageStorage?: TemporaryImageStorage;
}

export function buildApp(store = new InMemoryStore(), options: AppOptions = {}) {
  const app = Fastify({ logger: false });
  const actions = options.actionRepository ?? new InMemoryActionRepository(store);
  const accountExports = options.accountExportRepository ?? new InMemoryAccountExportRepository(store);
  const memories = options.memoryRepository ?? new InMemoryMemoryRepository(store);
  const insights = options.insightRepository ?? new InMemoryInsightRepository(store);
  const insightService = new InsightService(insights, memories, options.insightGenerator ?? new EvidenceBackedInsightGenerator());
  const submissions = options.submissionRepository ?? new InMemorySubmissionRepository(store);
  const temporaryImages = options.temporaryImageStorage ?? new InMemoryTemporaryImageStorage();
  const authAdmin = options.authAdmin ?? new InMemoryAuthAdmin((accountId) => store.deleteAccount(accountId));
  const sensitiveSessions = options.sensitiveSessionVerifier ?? new InMemorySensitiveSessionVerifier();
  app.register(multipart, { limits: { files: 1, fileSize: 15 * 1024 * 1024, fields: 3, parts: 4 } });
  const verifyToken = options.allowInsecureAccountHeader
    ? undefined
    : createTokenVerifier(options.auth ?? authConfigFromEnv(process.env));
  app.setErrorHandler((error, _request, reply) => {
    if (error instanceof DomainError) return reply.code(domainErrorStatus(error.code)).send({ error: error.code, message: error.message });
    if (error instanceof ZodError) return reply.code(400).send({ error: "invalid_request", message: error.message });
    return reply.code(500).send({ error: "internal_error", message: "Request could not be completed" });
  });
  app.addHook("preHandler", async (request, reply) => {
    if (request.url === "/health") return;
    if (options.allowInsecureAccountHeader) {
      const accountId = request.headers["x-account-id"];
      if (typeof accountId !== "string" || accountId.length === 0) return reply.code(401).send({ error: "unauthorized" });
      request.accountId = accountId;
      return;
    }
    const identity = await verifyToken!(request.headers.authorization);
    request.accountId = identity.accountId;
    if (identity.sessionId) request.sessionId = identity.sessionId;
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
  app.get<{ Params: { id: string } }>("/v1/submissions/:id/extraction", async (request, reply) => {
    const id = submissionFieldsInput.shape.submissionId.parse(request.params.id);
    if (!await submissions.find(request.accountId, id)) return reply.code(404).send({ error: "not_found" });
    const extraction = await submissions.findExtraction(request.accountId, id);
    return extraction ? publicExtraction(extraction) : reply.code(404).send({ error: "not_found" });
  });
  app.delete<{ Params: { id: string } }>("/v1/submissions/:id", async (request, reply) => {
    const id = submissionFieldsInput.shape.submissionId.parse(request.params.id);
    const submission = await submissions.find(request.accountId, id);
    if (!submission) return reply.code(204).send();
    await temporaryImages.remove(submission.imageObjectPath);
    await memories.removeSubmissionSource(request.accountId, id);
    await submissions.remove(request.accountId, id);
    return reply.code(204).send();
  });
  app.get<{ Params: { id: string } }>("/v1/submissions/:id/action-cards", async (request, reply) => {
    const id = submissionFieldsInput.shape.submissionId.parse(request.params.id);
    if (!await submissions.find(request.accountId, id)) return reply.code(404).send({ error: "not_found" });
    return { items: await actions.listForSubmission(request.accountId, id) };
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
      return editCard(
        card,
        input.fields,
        card.evidence,
        input.resolvedValidationIssues,
        input.targetAccountId ?? card.targetAccountId,
        input.type ?? card.type,
      );
    });
    return edited ?? reply.code(404).send({ error: "not_found" });
  });
  app.delete<{ Params: { id: string } }>("/v1/action-cards/:id", async (request, reply) => {
    const current = await actions.find(request.accountId, request.params.id);
    if (!current) return reply.code(404).send({ error: "not_found" });
    if (current.status === "executing" || current.status === "succeeded") {
      throw new DomainError("card_not_cancellable", "Card can no longer be cancelled");
    }
    if (current.status === "cancelled") return reply.code(204).send();
    if (current.confirmedSnapshot) {
      const cancelled = await actions.recordExecution(
        request.accountId,
        current.id,
        cancellationReceiptId(current.id, current.confirmedSnapshot.version),
        { status: "cancelled", errorCode: "user_cancelled", errorMessage: "User cancelled before provider write" },
      );
      if (!cancelled) return reply.code(404).send({ error: "not_found" });
      return reply.code(204).send();
    }
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
    await insightService.ensureForReceipt(result.card, result.receipt);
    return reply.code(201).send(result.receipt);
  });
  app.get("/v1/insights", async (request) => {
    const query = insightSearchInput.parse(request.query);
    return { items: await insights.list(request.accountId, query.submissionId) };
  });
  app.get("/v1/account/export", async (request) => {
    await sensitiveSessions.verify(request.accountId, request.sessionId);
    return accountExports.create(request.accountId);
  });
  app.delete("/v1/account", async (request, reply) => {
    await sensitiveSessions.verify(request.accountId, request.sessionId);
    await temporaryImages.removeAccount(request.accountId);
    await authAdmin.deleteUser(request.accountId);
    return reply.code(204).send();
  });
  app.get("/v1/memories", async (request) => {
    const query = memorySearchInput.parse(request.query);
    return {
      items: await memories.listActive(request.accountId, {
        ...(query.q ? { search: query.q } : {}),
        ...(query.subjectRef ? { subjectRef: query.subjectRef } : {}),
        ...(query.type ? { type: query.type } : {}),
        ...(query.from ? { createdFrom: query.from } : {}),
        ...(query.to ? { createdTo: query.to } : {}),
        limit: query.limit,
      }),
    };
  });
  app.post("/v1/memories", async (request, reply) => {
    const input = memoryInput.parse(request.body);
    const memory = createMemory({
      accountId: request.accountId,
      ...input,
      sourceEvidence: input.sourceEvidence.map(({ messageId, ...evidence }) => ({
        ...evidence,
        ...(messageId ? { messageId } : {}),
      })),
    });
    return reply.code(201).send(await memories.create(memory));
  });
  app.patch<{ Params: { id: string } }>("/v1/memories/:id", async (request, reply) => {
    const input = memoryRevisionInput.parse(request.body);
    const revised = await memories.revise(request.accountId, request.params.id, input.assertion, input.sourceRef);
    if (!revised) return reply.code(404).send({ error: "not_found" });
    return reply.send(revised);
  });
  app.delete("/v1/memories", async (request) => ({ cleared: await memories.clear(request.accountId) }));
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

function publicExtraction(extraction: import("../domain/model.ts").ContextExtraction) {
  const { accountId: _accountId, ...visible } = extraction;
  return visible;
}

function cancellationReceiptId(cardId: string, version: number): string {
  const hex = createHash("sha256").update(`${cardId}:cancel:${version}`).digest("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-8${hex.slice(17, 20)}-${hex.slice(20, 32)}`;
}

function domainErrorStatus(code: string): number {
  if (code === "unauthorized") return 401;
  if (code === "image_too_large") return 413;
  if (code === "unsupported_image_type" || code === "image_type_mismatch") return 415;
  if (["image_empty", "image_required", "invalid_image_part", "supplemental_text_too_long"].includes(code)) return 400;
  return 409;
}

declare module "fastify" {
  interface FastifyRequest {
    accountId: string;
    sessionId?: string;
  }
}
