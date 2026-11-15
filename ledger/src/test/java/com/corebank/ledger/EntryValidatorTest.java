package com.corebank.ledger;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebank.common.DomainException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class EntryValidatorTest {
  @Test
  void balancedTwoLegEntryIsValid() {
    assertThatCode(
            () ->
                EntryValidator.validate(
                    List.of(new Posting(1, -500, "SGD"), new Posting(2, 500, "SGD"))))
        .doesNotThrowAnyException();
  }

  @Test
  void unbalancedEntryIsRejected() {
    assertThatThrownBy(
            () ->
                EntryValidator.validate(
                    List.of(new Posting(1, -500, "SGD"), new Posting(2, 499, "SGD"))))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("unbalanced in SGD by -1");
  }

  @Test
  void mustBalanceInEachCurrencySeparately() {
    // Sums to zero overall but not per currency.
    assertThatThrownBy(
            () ->
                EntryValidator.validate(
                    List.of(new Posting(1, -500, "SGD"), new Posting(2, 500, "USD"))))
        .isInstanceOf(DomainException.class);
    assertThatCode(
            () ->
                EntryValidator.validate(
                    List.of(
                        new Posting(1, -500, "SGD"),
                        new Posting(2, 500, "SGD"),
                        new Posting(3, -370, "USD"),
                        new Posting(4, 370, "USD"))))
        .doesNotThrowAnyException();
  }

  @Test
  void singleOrZeroPostingsAreRejected() {
    assertThatThrownBy(() -> EntryValidator.validate(List.of(new Posting(1, 0, "SGD"))))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(
            () ->
                EntryValidator.validate(
                    List.of(new Posting(1, 0, "SGD"), new Posting(2, 0, "SGD"))))
        .hasMessageContaining("non-zero");
  }

  /** Property: any random multi-leg entry closed by a balancing leg is valid; perturbing it is not. */
  @RepeatedTest(200)
  void randomBalancedEntriesAreValidAndPerturbedOnesAreNot() {
    Random rnd = new Random();
    List<Posting> legs = new ArrayList<>();
    long sum = 0;
    int n = 1 + rnd.nextInt(6);
    for (int i = 0; i < n; i++) {
      long amt = rnd.nextLong(1, 1_000_000) * (rnd.nextBoolean() ? 1 : -1);
      legs.add(new Posting(i, amt, "SGD"));
      sum += amt;
    }
    if (sum == 0) {
      return;
    }
    legs.add(new Posting(99, -sum, "SGD"));
    assertThatCode(() -> EntryValidator.validate(legs)).doesNotThrowAnyException();
    legs.set(0, new Posting(0, legs.get(0).amount() + 1, "SGD"));
    if (legs.get(0).amount() != 0) {
      assertThatThrownBy(() -> EntryValidator.validate(legs)).isInstanceOf(DomainException.class);
    }
  }
}
