package com.corebank.ledger;

import com.corebank.common.DomainException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The double-entry invariant, checked before anything is written. */
public final class EntryValidator {
  private EntryValidator() {}

  /**
   * A valid entry has at least two non-zero postings and its postings sum to zero in every
   * currency, so money only moves between accounts and is never created or destroyed.
   */
  public static void validate(List<Posting> postings) {
    if (postings == null || postings.size() < 2) {
      throw DomainException.invalid("an entry needs at least two postings");
    }
    Map<String, Long> sums = new HashMap<>();
    for (Posting p : postings) {
      if (p.amount() == 0) {
        throw DomainException.invalid("posting amount must be non-zero");
      }
      sums.merge(p.currency(), p.amount(), Math::addExact);
    }
    sums.forEach(
        (currency, sum) -> {
          if (sum != 0) {
            throw DomainException.invalid("entry is unbalanced in " + currency + " by " + sum);
          }
        });
  }
}
