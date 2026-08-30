import { recordExecution } from "../domain/action-card.ts";
import type { ActionCard } from "../domain/model.ts";
import type { ActionRepository, ExecutionResult } from "./action-repository.ts";
import type { InMemoryStore } from "./in-memory-store.ts";

export class InMemoryActionRepository implements ActionRepository {
  constructor(private readonly store: InMemoryStore) {}

  async create(card: ActionCard): Promise<ActionCard> {
    const existing = this.store.card(card.accountId, card.id);
    if (existing) return existing;
    this.store.cards.set(card.id, structuredClone(card));
    return structuredClone(card);
  }

  async find(accountId: string, id: string): Promise<ActionCard | undefined> {
    return this.store.card(accountId, id);
  }

  async mutate(
    accountId: string,
    id: string,
    transition: (card: ActionCard) => ActionCard,
  ): Promise<ActionCard | undefined> {
    const current = this.store.card(accountId, id);
    if (!current) return undefined;
    const updated = transition(current);
    this.store.cards.set(updated.id, structuredClone(updated));
    return structuredClone(updated);
  }

  async recordExecution(accountId: string, cardId: string, receiptId: string, result: ExecutionResult) {
    const existing = this.store.receipts.find((receipt) => receipt.accountId === accountId && receipt.id === receiptId);
    if (existing) {
      const card = this.store.card(accountId, cardId);
      return card && existing.actionCardId === cardId
        ? { card, receipt: structuredClone(existing) }
        : undefined;
    }
    const card = this.store.card(accountId, cardId);
    if (!card) return undefined;
    const previous = this.store.receipts.filter((receipt) => receipt.accountId === accountId && receipt.actionCardId === cardId);
    const recorded = recordExecution(card, result, previous, new Date(), receiptId);
    this.store.cards.set(cardId, structuredClone(recorded.card));
    this.store.receipts.push(structuredClone(recorded.receipt));
    return structuredClone(recorded);
  }
}
