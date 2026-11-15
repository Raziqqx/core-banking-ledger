package com.corebank.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebank.accounts.AccountRepository;
import com.corebank.accounts.LockingMode;
import com.corebank.common.DomainException;
import com.corebank.ledger.LedgerAuditor;
import com.corebank.ledger.LedgerService;
import com.corebank.payments.Payment;
import com.corebank.payments.PaymentRequest;
import com.corebank.payments.PaymentSaga;
import com.corebank.payments.PaymentStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * N threads move money randomly between a small set of accounts (high contention). Afterwards the
 * total is unchanged, no balance is negative, and the auditor finds no inconsistency.
 */
class ConcurrencyIT extends IntegrationTestBase {
  static final int ACCOUNTS = 10;
  static final long INITIAL = 100_000; // 1,000.00 each
  static final int THREADS = 16;
  static final int OPS_PER_THREAD = 250;

  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate tx;
  @Autowired AccountRepository accounts;
  @Autowired LedgerAuditor auditor;
  @Autowired PaymentStore payments;
  @Autowired PaymentSaga saga;

  private long[] openFunded(LedgerService ledger) {
    long[] ids = new long[ACCOUNTS];
    for (int i = 0; i < ACCOUNTS; i++) {
      ids[i] = accounts.insert("stress", "SGD", 0).id();
      ledger.deposit(ids[i], INITIAL);
    }
    return ids;
  }

  private interface Op {
    void run(long from, long to, long amount);
  }

  /** Runs THREADS x OPS_PER_THREAD random ops; returns {ok, rejected, millis}. */
  private long[] hammer(long[] ids, Op op) throws Exception {
    AtomicLong ok = new AtomicLong();
    AtomicLong rejected = new AtomicLong();
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    long start = System.nanoTime();
    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < THREADS; t++) {
      futures.add(pool.submit(() -> {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < OPS_PER_THREAD; i++) {
          int from = rnd.nextInt(ACCOUNTS);
          int to = (from + 1 + rnd.nextInt(ACCOUNTS - 1)) % ACCOUNTS;
          try {
            op.run(ids[from], ids[to], rnd.nextLong(1, 30_000));
            ok.incrementAndGet();
          } catch (DomainException insufficientFunds) {
            rejected.incrementAndGet();
          }
        }
      }));
    }
    for (Future<?> f : futures) {
      f.get(); // rethrows anything unexpected (deadlock, exhausted retries, ...)
    }
    pool.shutdown();
    return new long[] {ok.get(), rejected.get(), (System.nanoTime() - start) / 1_000_000};
  }

  private void assertInvariants(long[] ids) {
    long sum = 0;
    for (long id : ids) {
      var a = accounts.get(id);
      assertThat(a.balance()).as("balance of %d", id).isNotNegative();
      assertThat(a.held()).as("held of %d", id).isZero();
      sum += a.balance();
    }
    assertThat(sum).isEqualTo(ACCOUNTS * INITIAL);
    assertThat(auditor.audit().ok()).isTrue();
  }

  @ParameterizedTest
  @EnumSource(LockingMode.class)
  void randomTransfersConserveMoney(LockingMode mode) throws Exception {
    LedgerService ledger = new LedgerService(jdbc, tx, accounts, mode);
    long[] ids = openFunded(ledger);

    long[] r = hammer(ids, (from, to, amount) -> ledger.transfer(from, to, amount, "stress"));

    assertThat(r[0] + r[1]).isEqualTo((long) THREADS * OPS_PER_THREAD);
    assertThat(r[0]).isPositive();
    assertInvariants(ids);
    record("transfer-" + mode, r, ledger.optimisticRetries());
  }

  @Test
  void concurrentSagaPaymentsConserveMoney() throws Exception {
    LedgerService ledger = new LedgerService(jdbc, tx, accounts, LockingMode.PESSIMISTIC);
    long[] ids = openFunded(ledger);

    long[] r = hammer(ids, (from, to, amount) -> {
      Payment p = saga.run(payments.create(new PaymentRequest(from, to, amount, "SGD", null)));
      if (p.status() != Payment.Status.COMPLETED) {
        throw new DomainException(422, p.status().name(), p.failureReason());
      }
    });

    assertThat(r[0]).isPositive();
    assertInvariants(ids);
    record("saga-PESSIMISTIC", r, 0);
  }

  /** Appends the run's numbers to results/ so they can be cited (never hand-written). */
  private static void record(String scenario, long[] r, long retries) throws IOException {
    Path out = Path.of("..", "results", "concurrency_it.csv");
    Files.createDirectories(out.getParent());
    if (!Files.exists(out)) {
      Files.writeString(out, "timestamp,scenario,accounts,threads,ops,committed,rejected_insufficient_funds,optimistic_retries,millis,ops_per_sec\n");
    }
    long ops = (long) THREADS * OPS_PER_THREAD;
    Files.writeString(out, String.join(",", Instant.now().toString(), scenario,
        String.valueOf(ACCOUNTS), String.valueOf(THREADS), String.valueOf(ops), String.valueOf(r[0]),
        String.valueOf(r[1]), String.valueOf(retries), String.valueOf(r[2]),
        String.valueOf(ops * 1000 / Math.max(1, r[2]))) + "\n", StandardOpenOption.APPEND);
  }
}
