import { createClient, type SupabaseClient } from "@supabase/supabase-js";

export interface AuthAdmin {
  deleteUser(accountId: string): Promise<void>;
}

export class SupabaseAuthAdmin implements AuthAdmin {
  constructor(private readonly client: SupabaseClient) {}

  async deleteUser(accountId: string): Promise<void> {
    const { error } = await this.client.auth.admin.deleteUser(accountId, false);
    if (!error || error.status === 404) return;
    throw error;
  }
}

export class InMemoryAuthAdmin implements AuthAdmin {
  readonly deletedUserIds: string[] = [];

  constructor(private readonly onDelete: (accountId: string) => void = () => undefined) {}

  async deleteUser(accountId: string): Promise<void> {
    this.onDelete(accountId);
    if (!this.deletedUserIds.includes(accountId)) this.deletedUserIds.push(accountId);
  }
}

export function createAuthAdmin(env: NodeJS.ProcessEnv): AuthAdmin {
  const url = env.SUPABASE_URL;
  const secretKey = env.SUPABASE_SECRET_KEY;
  if (!url || !secretKey) return new UnavailableAuthAdmin("SUPABASE_URL and SUPABASE_SECRET_KEY are required for account deletion");
  const client = createClient(url, secretKey, {
    auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
  });
  return new SupabaseAuthAdmin(client);
}

class UnavailableAuthAdmin implements AuthAdmin {
  constructor(private readonly reason: string) {}
  async deleteUser(): Promise<never> { throw new Error(this.reason); }
}
