package com.corebank.accounts;

import com.corebank.common.DomainException;

/**
 * Account snapshot. Amounts are in minor units (cents) of {@code currency}.
 *
 * <p>{@code balance} is a cache of the sum of the account's postings (the ledger is the source of
 * truth; {@code LedgerAuditor} checks they agree). {@code held} is money reserved by active holds,
 * so {@code available = balance - held}. {@code version} drives optimistic locking.
 */
public record Account(
    long id,
    String owner,
    String currency,
    long balance,
    long held,
    long overdraftLimit,
    boolean system,
    long version) {

  public long available() {
    return balance - held;
  }

  /**
   * Returns this account with the deltas applied, enforcing the no-overdraft invariant: a change
   * that lowers {@code available} must leave it at or above {@code -overdraftLimit}. System
   * (funding) accounts are exempt because they represent the bank's side of deposits.
   */
  public Account apply(long balanceDelta, long heldDelta) {
    long newBalance = Math.addExact(balance, balanceDelta);
    long newHeld = Math.addExact(held, heldDelta);
    if (newHeld < 0) {
      throw new IllegalStateException("held amount would go negative on account " + id);
    }
    long newAvailable = newBalance - newHeld;
    if (!system && newAvailable < available() && newAvailable < -overdraftLimit) {
      throw DomainException.insufficientFunds(id);
    }
    return new Account(id, owner, currency, newBalance, newHeld, overdraftLimit, system, version);
  }
}
