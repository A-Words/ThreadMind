import type { ActionCard, ActionReceipt, InsightBundle, MemoryRecord } from "../domain/model.js";

export class InMemoryStore {
  readonly cards = new Map<string, ActionCard>();
  readonly receipts: ActionReceipt[] = [];
  readonly memories = new Map<string, MemoryRecord>();
  readonly insights = new Map<string, InsightBundle>();

  card(accountId: string, id: string): ActionCard | undefined {
    const card = this.cards.get(id);
    return card?.accountId === accountId ? structuredClone(card) : undefined;
  }

  activeMemories(accountId: string): MemoryRecord[] {
    return [...this.memories.values()].filter((memory) => memory.accountId === accountId && memory.status === "active").map((memory) => structuredClone(memory));
  }
}
