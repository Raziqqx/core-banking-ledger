package com.corebank.accounts;

/** How concurrent writers to the same account are serialised. Compared in the stress tests. */
public enum LockingMode {
  /** SELECT ... FOR UPDATE, rows locked in ascending id order so writers cannot deadlock. */
  PESSIMISTIC,
  /** Plain read, then UPDATE ... WHERE version = ?; the transaction is retried on conflict. */
  OPTIMISTIC
}
