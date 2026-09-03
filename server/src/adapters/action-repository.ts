import type { ActionCard, ActionReceipt, ContactContextSnapshot } from "../domain/model.ts";

export type ExecutionResult =
  | { status: "succeeded"; targetRecordId: string; contactContext?: ContactContextSnapshot }
  | { status: "failed" | "cancelled"; errorCode?: string; errorMessage?: string };

export interface ActionRepository {
  create(card: ActionCard): Promise<ActionCard>;
  find(accountId: string, id: string): Promise<ActionCard | undefined>;
  listForSubmission(accountId: string, submissionId: string): Promise<ActionCard[]>;
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
