import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import { createHash } from "node:crypto";
import { DomainError } from "../domain/errors.ts";
import type { TemporaryImageStorage } from "./temporary-image-storage.ts";

export class SupabaseTemporaryImageStorage implements TemporaryImageStorage {
  constructor(
    private readonly client: SupabaseClient,
    private readonly bucket: string,
  ) {}

  async putIfAbsent(path: string, bytes: Uint8Array, contentType: string, sha256: string): Promise<void> {
    const { error } = await this.client.storage.from(this.bucket).upload(path, bytes, {
      contentType,
      cacheControl: "0",
      upsert: false,
    });
    if (!error) return;
    const { data: existing, error: downloadError } = await this.client.storage.from(this.bucket).download(path);
    if (downloadError || !existing) throw error;
    const existingSha256 = createHash("sha256").update(new Uint8Array(await existing.arrayBuffer())).digest("hex");
    if (existingSha256 !== sha256 || (existing.type && existing.type !== contentType)) {
      throw new DomainError("submission_conflict", "Storage path already has different content");
    }
  }

  async remove(path: string): Promise<void> {
    const { error } = await this.client.storage.from(this.bucket).remove([path]);
    if (error) throw error;
  }
}

export function createTemporaryImageStorage(env: NodeJS.ProcessEnv): TemporaryImageStorage {
  const url = env.SUPABASE_URL;
  const secretKey = env.SUPABASE_SECRET_KEY;
  if (!url || !secretKey) return new UnavailableTemporaryImageStorage("SUPABASE_URL and SUPABASE_SECRET_KEY are required for screenshot uploads");
  const client = createClient(url, secretKey, {
    auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
  });
  return new SupabaseTemporaryImageStorage(client, env.SUPABASE_STORAGE_BUCKET ?? "threadmind-submissions");
}

class UnavailableTemporaryImageStorage implements TemporaryImageStorage {
  constructor(private readonly reason: string) {}
  async putIfAbsent(): Promise<never> { throw new Error(this.reason); }
  async remove(): Promise<never> { throw new Error(this.reason); }
}
