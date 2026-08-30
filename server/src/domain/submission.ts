import { createHash, randomUUID } from "node:crypto";
import { invariant } from "./errors.ts";
import type { BackgroundJob, ScreenshotSubmission } from "./model.ts";

export const MAX_SCREENSHOT_BYTES = 15 * 1024 * 1024;
export const SCREENSHOT_CONTENT_TYPES = ["image/png", "image/jpeg", "image/webp"] as const;

export function prepareSubmission(input: {
  id: string;
  accountId: string;
  image: Uint8Array;
  contentType: ScreenshotSubmission["imageContentType"];
  source: ScreenshotSubmission["source"];
  supplementalText?: string;
}, now = new Date()): { submission: ScreenshotSubmission; job: BackgroundJob } {
  invariant(input.image.byteLength > 0, "image_empty", "Screenshot is empty");
  invariant(input.image.byteLength <= MAX_SCREENSHOT_BYTES, "image_too_large", "Screenshot exceeds 15 MiB");
  invariant(matchesContentType(input.image, input.contentType), "image_type_mismatch", "Screenshot bytes do not match the declared content type");
  const timestamp = now.toISOString();
  const supplementalText = input.supplementalText?.trim();
  invariant(!supplementalText || supplementalText.length <= 4000, "supplemental_text_too_long", "Supplemental text exceeds 4000 characters");
  const submission: ScreenshotSubmission = {
    id: input.id,
    accountId: input.accountId,
    imageObjectPath: `${input.accountId}/${input.id}`,
    imageContentType: input.contentType,
    imageByteSize: input.image.byteLength,
    imageSha256: createHash("sha256").update(input.image).digest("hex"),
    ...(supplementalText ? { supplementalText } : {}),
    source: input.source,
    status: "uploaded",
    createdAt: timestamp,
    updatedAt: timestamp,
  };
  const job: BackgroundJob = {
    id: randomUUID(),
    accountId: input.accountId,
    type: "analyze_submission",
    aggregateId: input.id,
    idempotencyKey: `analyze:${input.id}`,
    status: "queued",
    attempt: 0,
    maxAttempts: 5,
    availableAt: timestamp,
    createdAt: timestamp,
    updatedAt: timestamp,
  };
  return { submission, job };
}

function matchesContentType(bytes: Uint8Array, contentType: ScreenshotSubmission["imageContentType"]): boolean {
  if (contentType === "image/png") {
    return bytes.length >= 8 && [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a].every((value, index) => bytes[index] === value);
  }
  if (contentType === "image/jpeg") return bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
  return bytes.length >= 12
    && String.fromCharCode(...bytes.slice(0, 4)) === "RIFF"
    && String.fromCharCode(...bytes.slice(8, 12)) === "WEBP";
}

export function sameSubmissionContent(left: ScreenshotSubmission, right: ScreenshotSubmission): boolean {
  return left.imageSha256 === right.imageSha256
    && left.imageContentType === right.imageContentType
    && left.imageByteSize === right.imageByteSize
    && left.source === right.source
    && left.supplementalText === right.supplementalText;
}
