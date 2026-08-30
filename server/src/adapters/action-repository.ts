import type { ActionCard, ActionReceipt } from "../domain/model.ts";

export type ExecutionResult =
  | { status: "succeeded"; targetRecordId: string }
  | { status: "failed" | "cancelled"; errorCode?: string; errorMessage?: string };

export interface ActionRepository {
  create(card: ActionCard): Promise<ActionCard>;
  find(accountId: string, id: string): Promise<ActionCard | undefined>;
  mutate(
    accountId: string,
    id: string,
    transition: (card: ActionCard) => ActionCard,
  ): Promise<ActionCard | undefined>;
  recordExecution(
    accountId: string,
    cardId: string,
    receiptId: string,
    result: ExecutionResult,
  ): Promise<{ card: ActionCard; receipt: ActionReceipt } | undefined>;
}
