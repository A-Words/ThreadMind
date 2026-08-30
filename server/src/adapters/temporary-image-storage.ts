import { DomainError } from "../domain/errors.ts";

export interface TemporaryImageStorage {
  putIfAbsent(path: string, bytes: Uint8Array, contentType: string, sha256: string): Promise<void>;
  remove(path: string): Promise<void>;
}

export class InMemoryTemporaryImageStorage implements TemporaryImageStorage {
  private readonly objects = new Map<string, { bytes: Uint8Array; contentType: string; sha256: string }>();

  async putIfAbsent(path: string, bytes: Uint8Array, contentType: string, sha256: string): Promise<void> {
    const existing = this.objects.get(path);
    if (existing) {
      if (existing.sha256 !== sha256 || existing.contentType !== contentType) throw new DomainError("submission_conflict", "Storage path already has different content");
      return;
    }
    this.objects.set(path, { bytes: Uint8Array.from(bytes), contentType, sha256 });
  }

  async remove(path: string): Promise<void> {
    this.objects.delete(path);
  }
}
