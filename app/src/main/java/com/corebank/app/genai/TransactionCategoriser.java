package com.corebank.app.genai;

/**
 * Labels a transaction with a spending category. Implementations must never throw for bad model
 * output: an unusable answer is {@link Category#OTHER}, because a category is advisory and must
 * never block or alter money movement.
 */
public interface TransactionCategoriser {
  /** Only what the label needs: no account ids, owners or balances leave the service. */
  record Transaction(String reference, long amountMinor, String currency) {}

  record Result(Category category, String provider) {}

  Result categorise(Transaction tx);
}
