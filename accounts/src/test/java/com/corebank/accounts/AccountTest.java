package com.corebank.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebank.common.DomainException;
import org.junit.jupiter.api.Test;

class AccountTest {
  private static Account acct(long balance, long held, long overdraft, boolean system) {
    return new Account(1, "alice", "SGD", balance, held, overdraft, system, 0);
  }

  @Test
  void debitWithinAvailableSucceeds() {
    assertThat(acct(1000, 0, 0, false).apply(-1000, 0).balance()).isZero();
  }

  @Test
  void debitBeyondAvailableIsRejected() {
    assertThatThrownBy(() -> acct(1000, 0, 0, false).apply(-1001, 0))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("INSUFFICIENT_FUNDS");
  }

  @Test
  void heldFundsAreNotAvailable() {
    assertThatThrownBy(() -> acct(1000, 600, 0, false).apply(-500, 0))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> acct(1000, 600, 0, false).apply(0, 500))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void overdraftAllowedUpToLimit() {
    assertThat(acct(100, 0, 500, false).apply(-600, 0).balance()).isEqualTo(-500);
    assertThatThrownBy(() -> acct(100, 0, 500, false).apply(-601, 0))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void capturingAHoldLeavesAvailableUnchanged() {
    Account a = acct(1000, 400, 0, false).apply(-400, -400);
    assertThat(a.balance()).isEqualTo(600);
    assertThat(a.held()).isZero();
  }

  @Test
  void creditsAlwaysAllowedEvenWhenOverdrawn() {
    // An account already below its limit (e.g. limit lowered later) can still receive money.
    assertThat(acct(-100, 0, 0, false).apply(50, 0).balance()).isEqualTo(-50);
  }

  @Test
  void systemAccountMayGoNegative() {
    assertThat(acct(0, 0, 0, true).apply(-1_000_000, 0).balance()).isEqualTo(-1_000_000);
  }

  @Test
  void heldCannotGoNegative() {
    assertThatThrownBy(() -> acct(1000, 0, 0, false).apply(0, -1))
        .isInstanceOf(IllegalStateException.class);
  }
}
