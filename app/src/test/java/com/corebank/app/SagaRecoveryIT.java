package com.corebank.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebank.ledger.LedgerAuditor;
import com.corebank.ledger.LedgerService;
import com.corebank.payments.IdempotencyService;
import com.corebank.payments.OutboxRepository;
import com.corebank.payments.Payment;
import com.corebank.payments.PaymentRequest;
import com.corebank.payments.PaymentSaga;
import com.corebank.payments.PaymentStore;
import com.corebank.payments.SagaRecovery;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Saga failure handling against real Postgres: a failure while recording progress after a ledger
 * call (the compensation-registration gap), and crashes simulated by leaving a payment mid-saga in
 * the database and then running {@link SagaRecovery}.
 */
class SagaRecoveryIT extends IntegrationTestBase {
  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate tx;
  @Autowired LedgerService ledger;
  @Autowired LedgerAuditor auditor;
  @Autowired PaymentStore store;
  @Autowired OutboxRepository outbox;
  @Autowired IdempotencyService idempotency;
  @Autowired SagaRecovery recovery;

  private static final String SUBJECT = "test-admin"; // the subject of adminBearer()

  // ---- compensation gap: progress() fails after the ledger call succeeded ----

  /** A store whose progress() fails on the given status, as a DB blip would. */
  private PaymentStore failingProgressOn(Payment.Status failOn) {
    return new PaymentStore(jdbc, outbox) {
      @Override
      public void progress(UUID id, Payment.Status status, Long holdId, Long entryId) {
        if (status == failOn) {
          throw new IllegalStateException("injected progress failure at " + status);
        }
        super.progress(id, status, holdId, entryId);
      }

      @Override
      public Payment finish(Payment p, Payment.Status s, Long h, Long e, String reason) {
        // Not a Spring proxy, so open the transaction the outbox write requires.
        return tx.execute(t -> super.finish(p, s, h, e, reason));
      }
    };
  }

  private void assertProgressFailureIsUndone(Payment.Status failOn) throws Exception {
    long a = openAccount("SGD", 10_000, 0);
    long b = openAccount("SGD", 0, 0);
    PaymentStore failing = failingProgressOn(failOn);
    PaymentSaga saga = new PaymentSaga(ledger, failing, new SimpleMeterRegistry());

    Payment p = saga.run(store.create(new PaymentRequest(a, b, 4_000, "SGD", "rent")));

    assertThat(p.status()).isEqualTo(Payment.Status.COMPENSATED);
    assertThat(balance(a).get("balance").asLong()).isEqualTo(10_000);
    assertThat(balance(a).get("held").asLong()).isZero();
    assertThat(balance(b).get("balance").asLong()).isZero();
    assertThat(activeHolds(a)).isZero();
    assertThat(auditor.audit().ok()).isTrue();
  }

  @Test
  void progressFailureAfterHoldReleasesTheHold() throws Exception {
    assertProgressFailureIsUndone(Payment.Status.RESERVED);
  }

  @Test
  void progressFailureAfterCaptureReversesTheTransfer() throws Exception {
    assertProgressFailureIsUndone(Payment.Status.POSTED);
  }

  // ---- crash recovery ----

  /** A payment whose request claimed an idempotency key and then "crashed" mid-saga. */
  private record Crashed(Payment payment, String clientKey, PaymentRequest request) {}

  private Crashed startPayment(long from, long to, long amount) {
    PaymentRequest req = new PaymentRequest(from, to, amount, "SGD", "invoice-7");
    String clientKey = UUID.randomUUID().toString();
    assertThat(idempotency.begin(SUBJECT + ":" + clientKey, req))
        .isInstanceOf(IdempotencyService.Claimed.class);
    Payment p = store.create(req);
    idempotency.attach(SUBJECT + ":" + clientKey, p.id());
    return new Crashed(p, clientKey, req);
  }

  /** Makes the payment look abandoned: no progress for an hour (well past the timeout). */
  private void age(UUID paymentId) {
    jdbc.sql("UPDATE payments SET updated_at = now() - interval '1 hour' WHERE id = ?")
        .param(paymentId).update();
    jdbc.sql("UPDATE idempotency_keys SET created_at = now() - interval '1 hour' WHERE payment_id = ?")
        .param(paymentId).update();
  }

  private long activeHolds(long account) {
    return jdbc.sql("SELECT count(*) FROM holds WHERE account_id = ? AND status = 'ACTIVE'")
        .param(account).query(Long.class).single();
  }

  private Payment.Status status(UUID id) {
    return store.get(id).status();
  }

  private MvcResult retry(Crashed c) throws Exception {
    return mvc.perform(postJson("/payments", Map.of(
                "fromAccount", c.request().fromAccount(), "toAccount", c.request().toAccount(),
                "amount", c.request().amount(), "currency", "SGD", "reference", "invoice-7"))
            .header("Idempotency-Key", c.clientKey())
            .header("Authorization", adminBearer()))
        .andReturn();
  }

  @Test
  void crashAfterHoldBeforeProgressReleasesTheHoldAndCompletesTheKey() throws Exception {
    long a = openAccount("SGD", 10_000, 0);
    long b = openAccount("SGD", 0, 0);
    Crashed c = startPayment(a, b, 3_000);
    // The saga placed the hold, then died before recording hold_id: only the reference links them.
    ledger.placeHold(a, 3_000, "SGD", "payment:" + c.payment().id());
    age(c.payment().id());
    assertThat(balance(a).get("held").asLong()).isEqualTo(3_000);
    MvcResult beforeRecovery = retry(c);
    assertThat(beforeRecovery.getResponse().getStatus()).isEqualTo(409);

    recovery.recoverStale();

    assertThat(status(c.payment().id())).isEqualTo(Payment.Status.COMPENSATED);
    assertThat(balance(a).get("held").asLong()).isZero();
    assertThat(balance(a).get("balance").asLong()).isEqualTo(10_000);
    assertThat(activeHolds(a)).isZero();
    assertThat(auditor.audit().ok()).isTrue();
    MvcResult replay = retry(c);
    assertThat(replay.getResponse().getStatus()).isEqualTo(422);
    JsonNode body = json.readTree(replay.getResponse().getContentAsString());
    assertThat(body.get("id").asText()).isEqualTo(c.payment().id().toString());
    assertThat(body.get("status").asText()).isEqualTo("COMPENSATED");
    assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND type = 'PaymentFailed'")
        .param(c.payment().id().toString()).query(Long.class).single()).isEqualTo(1);
  }

  @Test
  void crashAfterCaptureReversesThePostedTransfer() throws Exception {
    long a = openAccount("SGD", 10_000, 0);
    long b = openAccount("SGD", 0, 0);
    Crashed c = startPayment(a, b, 2_500);
    UUID id = c.payment().id();
    long hold = ledger.placeHold(a, 2_500, "SGD", "payment:" + id);
    store.progress(id, Payment.Status.RESERVED, hold, null);
    long entry = ledger.captureHold(hold, b, "payment " + id);
    // Died before recording POSTED / entry_id.
    age(id);
    assertThat(balance(b).get("balance").asLong()).isEqualTo(2_500);

    recovery.recoverStale();
    recovery.recoverStale(); // a second pass must not reverse again

    Payment p = store.get(id);
    assertThat(p.status()).isEqualTo(Payment.Status.COMPENSATED);
    assertThat(p.entryId()).isEqualTo(entry);
    assertThat(balance(a).get("balance").asLong()).isEqualTo(10_000);
    assertThat(balance(a).get("held").asLong()).isZero();
    assertThat(balance(b).get("balance").asLong()).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM journal_entries WHERE reverses = ?")
        .param(entry).query(Long.class).single()).isEqualTo(1);
    assertThat(auditor.audit().ok()).isTrue();
    assertThat(retry(c).getResponse().getStatus()).isEqualTo(422);
  }

  @Test
  void crashBeforeAnyLedgerCallEndsFailed() throws Exception {
    long a = openAccount("SGD", 10_000, 0);
    long b = openAccount("SGD", 0, 0);
    Crashed c = startPayment(a, b, 1_000);
    age(c.payment().id());

    recovery.recoverStale();

    assertThat(status(c.payment().id())).isEqualTo(Payment.Status.FAILED);
    assertThat(balance(a).get("balance").asLong()).isEqualTo(10_000);
    assertThat(retry(c).getResponse().getStatus()).isEqualTo(422);
  }

  @Test
  void unlinkedStaleKeyIsDeletedSoARetryRunsAgain() throws Exception {
    long a = openAccount("SGD", 10_000, 0);
    long b = openAccount("SGD", 0, 0);
    PaymentRequest req = new PaymentRequest(a, b, 1_000, "SGD", "invoice-7");
    String clientKey = UUID.randomUUID().toString();
    idempotency.begin(SUBJECT + ":" + clientKey, req); // crashed before creating the payment
    jdbc.sql("UPDATE idempotency_keys SET created_at = now() - interval '1 hour' WHERE key = ?")
        .param(SUBJECT + ":" + clientKey).update();

    recovery.recoverStale();

    MvcResult r = retry(new Crashed(null, clientKey, req));
    assertThat(r.getResponse().getStatus()).isEqualTo(201);
    assertThat(balance(a).get("balance").asLong()).isEqualTo(9_000);
  }

  @Test
  void concurrentRecoveryInstancesCompensateEachPaymentExactlyOnce() throws Exception {
    long a = openAccount("SGD", 100_000, 0);
    long b = openAccount("SGD", 0, 0);
    List<Long> entries = new ArrayList<>();
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      Crashed c = startPayment(a, b, 1_000);
      long hold = ledger.placeHold(a, 1_000, "SGD", "payment:" + c.payment().id());
      if (i % 2 == 0) {
        entries.add(ledger.captureHold(hold, b, "payment " + c.payment().id()));
      }
      age(c.payment().id());
      ids.add(c.payment().id());
    }

    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<Integer>> runs = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        runs.add(pool.submit(recovery::recoverStale));
      }
      for (Future<Integer> f : runs) {
        f.get();
      }
    } finally {
      pool.shutdown();
    }

    for (UUID id : ids) {
      assertThat(status(id)).isEqualTo(Payment.Status.COMPENSATED);
    }
    for (long entry : entries) {
      assertThat(jdbc.sql("SELECT count(*) FROM journal_entries WHERE reverses = ?")
          .param(entry).query(Long.class).single()).isEqualTo(1);
    }
    assertThat(balance(a).get("balance").asLong()).isEqualTo(100_000);
    assertThat(balance(a).get("held").asLong()).isZero();
    assertThat(balance(b).get("balance").asLong()).isZero();
    assertThat(auditor.audit().ok()).isTrue();
  }
}
