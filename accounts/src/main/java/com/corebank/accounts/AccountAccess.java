package com.corebank.accounts;

import com.corebank.common.CurrentCaller;
import com.corebank.common.DomainException;
import org.springframework.stereotype.Component;

/**
 * Ownership rules: an admin may touch any account; a customer only accounts whose {@code owner} is
 * their JWT subject. System (funding) accounts belong to no customer.
 */
@Component
public class AccountAccess {
  private final AccountRepository accounts;
  private final CurrentCaller caller;

  public AccountAccess(AccountRepository accounts, CurrentCaller caller) {
    this.accounts = accounts;
    this.caller = caller;
  }

  /** Throws 403 unless the caller is an admin or owns the account (404 if it does not exist). */
  public void requireOwner(long accountId) {
    if (!caller.isAdmin() && !owns(accounts.get(accountId))) {
      throw forbidden();
    }
  }

  /** Throws 403 unless the caller is an admin or owns at least one of the accounts. */
  public void requireOwnerOfAny(long... accountIds) {
    if (caller.isAdmin()) {
      return;
    }
    for (long id : accountIds) {
      if (owns(accounts.get(id))) {
        return;
      }
    }
    throw forbidden();
  }

  private boolean owns(Account a) {
    return !a.system() && a.owner().equals(caller.subject());
  }

  private static DomainException forbidden() {
    return new DomainException(403, "FORBIDDEN", "account belongs to another customer");
  }
}
