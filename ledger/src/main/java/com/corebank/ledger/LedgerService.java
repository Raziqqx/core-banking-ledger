package com.corebank.ledger;

import com.corebank.accounts.Account;
import com.corebank.accounts.AccountRepository;
import com.corebank.accounts.LockingMode;
import com.corebank.common.DomainException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only writer of balances. Every public method runs in its own transaction, which keeps each
 * call atomic on its own, the same as it would be behind a remote API; the payments saga composes
 * them.
 */
@Service
public class LedgerService {
  private static final int MAX_ATTEMPTS = 50;

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final AccountRepository accounts;
  private final LockingMode mode;
  private final AtomicLong optimisticRetries = new AtomicLong();

  public LedgerService(
      JdbcClient jdbc,
      TransactionTemplate tx,
      AccountRepository accounts,
      @Value("${ledger.locking:PESSIMISTIC}") LockingMode mode) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.accounts = accounts;
    this.mode = mode;
  }

  public LockingMode mode() {
    return mode;
  }

  /** Number of transactions re-run after an optimistic-lock conflict (0 in PESSIMISTIC mode). */
  public long optimisticRetries() {
    return optimisticRetries.get();
  }

  /** Moves {@code amount} from one account to another in their shared currency. */
  public long transfer(long from, long to, long amount, String description) {
    requirePositive(amount);
    if (from == to) {
      throw DomainException.invalid("cannot transfer to the same account");
    }
    String currency = accounts.get(from).currency();
    return post(
        description,
        List.of(new Posting(from, -amount, currency), new Posting(to, amount, currency)),
        null);
  }

  /** Credits a customer account from the bank's funding account for that currency. */
  public long deposit(long accountId, long amount) {
    requirePositive(amount);
    String currency = accounts.get(accountId).currency();
    long system = accounts.systemAccountId(currency);
    return post(
        "deposit",
        List.of(new Posting(system, -amount, currency), new Posting(accountId, amount, currency)),
        null);
  }

  /**
   * Records a balanced journal entry and updates the cached balances atomically. Rejects the whole
   * entry if any account's currency differs from its posting's or any debit would overdraw.
   */
  public long post(String description, List<Posting> postings, Long reverses) {
    EntryValidator.validate(postings);
    // Sorted by account id: rows are written (and so row-locked) in one global order. Without it,
    // OPTIMISTIC mode deadlocks when two transfers touch the same pair in opposite directions.
    Map<Long, Long> deltas = new TreeMap<>();
    postings.forEach(p -> deltas.merge(p.accountId(), p.amount(), Math::addExact));
    return inTransaction(
        () -> {
          Map<Long, Account> locked = accounts.load(deltas.keySet(), mode);
          for (Posting p : postings) {
            if (!locked.get(p.accountId()).currency().equals(p.currency())) {
              throw DomainException.invalid("currency mismatch on account " + p.accountId());
            }
          }
          deltas.forEach((id, delta) -> accounts.save(locked.get(id).apply(delta, 0)));
          return insertEntry(description, postings, reverses);
        });
  }

  /** Reserves funds: they stay in the balance but are no longer available. */
  public long placeHold(long accountId, long amount, String currency) {
    requirePositive(amount);
    return inTransaction(
        () -> {
          Account a = accounts.load(List.of(accountId), mode).get(accountId);
          if (!a.currency().equals(currency)) {
            throw DomainException.invalid("currency mismatch on account " + accountId);
          }
          accounts.save(a.apply(0, amount));
          return jdbc.sql(
                  "INSERT INTO holds(account_id, amount, status) VALUES (?, ?, 'ACTIVE') RETURNING id")
              .params(accountId, amount)
              .query(Long.class)
              .single();
        });
  }

  /** Converts an active hold into a posted transfer to {@code toAccount}; returns the entry id. */
  public long captureHold(long holdId, long toAccount, String description) {
    return inTransaction(
        () -> {
          Hold h = transitionHold(holdId, "CAPTURED");
          if (h.accountId() == toAccount) {
            throw DomainException.invalid("cannot transfer to the same account");
          }
          Map<Long, Account> locked = accounts.load(List.of(h.accountId(), toAccount), mode);
          Account from = locked.get(h.accountId());
          Account to = locked.get(toAccount);
          if (!from.currency().equals(to.currency())) {
            throw DomainException.invalid("currency mismatch on account " + toAccount);
          }
          Account newFrom = from.apply(-h.amount(), -h.amount());
          Account newTo = to.apply(h.amount(), 0);
          // Write in ascending id order, as in post(), so concurrent writers cannot deadlock.
          for (Account a : from.id() < to.id() ? List.of(newFrom, newTo) : List.of(newTo, newFrom)) {
            accounts.save(a);
          }
          return insertEntry(
              description,
              List.of(
                  new Posting(from.id(), -h.amount(), from.currency()),
                  new Posting(to.id(), h.amount(), to.currency())),
              null);
        });
  }

  /**
   * Returns held funds to the available balance. Idempotent: releasing a hold that is already
   * released or captured is a no-op that returns false, so saga compensation can be retried.
   */
  public boolean releaseHold(long holdId) {
    return inTransaction(
        () -> {
          Hold h;
          try {
            h = transitionHold(holdId, "RELEASED");
          } catch (IllegalStateException alreadyFinal) {
            return false;
          }
          Account a = accounts.load(List.of(h.accountId()), mode).get(h.accountId());
          accounts.save(a.apply(0, -h.amount()));
          return true;
        });
  }

  /** Posts the mirror image of an entry. An entry can be reversed at most once (unique index). */
  public long reverse(long entryId) {
    List<Posting> original =
        jdbc.sql("SELECT account_id, amount, currency FROM postings WHERE entry_id = ? ORDER BY id")
            .param(entryId)
            .query((rs, i) -> new Posting(rs.getLong(1), -rs.getLong(2), rs.getString(3)))
            .list();
    if (original.isEmpty()) {
      throw DomainException.notFound("journal entry " + entryId);
    }
    return post("reversal of entry " + entryId, original, entryId);
  }

  public List<Map<String, Object>> history(long accountId, int limit) {
    accounts.get(accountId);
    return jdbc.sql(
            "SELECT e.id AS entry_id, e.description, p.amount, p.currency, e.created_at"
                + " FROM postings p JOIN journal_entries e ON e.id = p.entry_id"
                + " WHERE p.account_id = ? ORDER BY p.id DESC LIMIT ?")
        .params(accountId, limit)
        .query()
        .listOfRows();
  }

  private record Hold(long accountId, long amount) {}

  /** Moves a hold out of ACTIVE. The conditional UPDATE makes capture and release race-free. */
  private Hold transitionHold(long holdId, String target) {
    return jdbc.sql(
            "UPDATE holds SET status = ?, updated_at = now() WHERE id = ? AND status = 'ACTIVE'"
                + " RETURNING account_id, amount")
        .params(target, holdId)
        .query((rs, i) -> new Hold(rs.getLong(1), rs.getLong(2)))
        .optional()
        .orElseThrow(() -> new IllegalStateException("hold " + holdId + " is not active"));
  }

  private long insertEntry(String description, List<Posting> postings, Long reverses) {
    long entryId =
        jdbc.sql("INSERT INTO journal_entries(description, reverses) VALUES (?, ?) RETURNING id")
            .params(description, reverses)
            .query(Long.class)
            .single();
    for (Posting p : postings) {
      jdbc.sql("INSERT INTO postings(entry_id, account_id, amount, currency) VALUES (?, ?, ?, ?)")
          .params(entryId, p.accountId(), p.amount(), p.currency())
          .update();
    }
    return entryId;
  }

  /** Runs the work in a fresh transaction, re-running it when an optimistic version check fails. */
  private <T> T inTransaction(Supplier<T> work) {
    for (int attempt = 1; ; attempt++) {
      try {
        return tx.execute(status -> work.get());
      } catch (OptimisticLockingFailureException e) {
        if (attempt >= MAX_ATTEMPTS) {
          throw e;
        }
        optimisticRetries.incrementAndGet();
        backoff(attempt);
      }
    }
  }

  /**
   * Full-jitter exponential backoff, capped at 64 ms. The earlier linear 1-11 ms window let a hot
   * account starve one writer for all 50 attempts under 16 threads (see DECISIONS.md, M3).
   */
  private static void backoff(int attempt) {
    long cap = 1L << Math.min(attempt, 6);
    try {
      Thread.sleep(ThreadLocalRandom.current().nextLong(1, cap + 1));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while retrying", e);
    }
  }

  private static void requirePositive(long amount) {
    if (amount <= 0) {
      throw DomainException.invalid("amount must be positive");
    }
  }
}
