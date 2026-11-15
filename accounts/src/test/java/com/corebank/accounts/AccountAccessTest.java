package com.corebank.accounts;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.corebank.common.CurrentCaller;
import com.corebank.common.DomainException;
import org.junit.jupiter.api.Test;

class AccountAccessTest {
  private final AccountRepository repo = mock(AccountRepository.class);

  private static CurrentCaller caller(String sub, boolean admin) {
    return new CurrentCaller() {
      public String subject() {
        return sub;
      }

      public boolean isAdmin() {
        return admin;
      }
    };
  }

  private static Account acct(long id, String owner, boolean system) {
    return new Account(id, owner, "SGD", 0, 0, 0, system, 0);
  }

  @Test
  void ownerMayAccessOwnAccount() {
    when(repo.get(1)).thenReturn(acct(1, "alice", false));
    assertThatCode(() -> new AccountAccess(repo, caller("alice", false)).requireOwner(1))
        .doesNotThrowAnyException();
  }

  @Test
  void otherCustomerIsForbidden() {
    when(repo.get(1)).thenReturn(acct(1, "alice", false));
    assertThatThrownBy(() -> new AccountAccess(repo, caller("bob", false)).requireOwner(1))
        .isInstanceOfSatisfying(DomainException.class, e -> org.assertj.core.api.Assertions
            .assertThat(e.status()).isEqualTo(403));
  }

  @Test
  void systemAccountIsNeverOwnedByACustomer() {
    when(repo.get(9)).thenReturn(acct(9, "SYSTEM", true));
    assertThatThrownBy(() -> new AccountAccess(repo, caller("SYSTEM", false)).requireOwner(9))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void adminSkipsTheLookup() {
    new AccountAccess(repo, caller("ops", true)).requireOwnerOfAny(1, 2);
    verifyNoInteractions(repo);
  }

  @Test
  void ownerOfEitherSideMayReadAPayment() {
    when(repo.get(1)).thenReturn(acct(1, "alice", false));
    when(repo.get(2)).thenReturn(acct(2, "bob", false));
    assertThatCode(() -> new AccountAccess(repo, caller("bob", false)).requireOwnerOfAny(1, 2))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> new AccountAccess(repo, caller("eve", false)).requireOwnerOfAny(1, 2))
        .isInstanceOf(DomainException.class);
  }
}
