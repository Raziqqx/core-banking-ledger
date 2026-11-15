package com.corebank.payments;

import com.corebank.common.DomainException;
import com.corebank.ledger.LedgerService;
import com.corebank.ledger.LedgerService.HoldView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Finishes sagas whose process died mid-run. A payment still PENDING/RESERVED/POSTED after
 * {@code payments.recovery.timeout} without progress is compensated (its hold released, or its
 * posted transfer reversed), marked COMPENSATED or FAILED with a PaymentFailed event, and its
 * idempotency key is completed with a 422 so a client retry gets the stored result instead of 409.
 *
 * <p>Multi-instance safety: each payment is claimed with {@code SELECT ... FOR UPDATE SKIP LOCKED}
 * and recovered in that one transaction (the ledger calls join it), so two instances never work on
 * the same payment and a crash during recovery rolls everything back for the next tick. A late saga
 * that is merely slow, not dead, cannot undo this: the ledger's conditional hold transitions make
 * capture and release mutually exclusive, and {@link PaymentStore#finish} is first-writer-wins.
 */
@Component
@ConditionalOnProperty(name = "payments.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class SagaRecovery {
  private static final Logger log = LoggerFactory.getLogger(SagaRecovery.class);

  /** A compensation was refused by the ledger (e.g. the payee already spent the funds). */
  private static final class CompensationRefused extends RuntimeException {
    final UUID paymentId;

    CompensationRefused(UUID paymentId, String message, Throwable cause) {
      super(message, cause);
      this.paymentId = paymentId;
    }
  }

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final LedgerService ledger;
  private final PaymentStore store;
  private final ObjectMapper json;
  private final MeterRegistry meters;
  private final Duration timeout;
  private final int batchSize;

  public SagaRecovery(
      JdbcClient jdbc,
      TransactionTemplate tx,
      LedgerService ledger,
      PaymentStore store,
      ObjectMapper json,
      MeterRegistry meters,
      @Value("${payments.recovery.timeout:PT5M}") Duration timeout,
      @Value("${payments.recovery.batch-size:50}") int batchSize) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.ledger = ledger;
    this.store = store;
    this.json = json;
    this.meters = meters;
    this.timeout = timeout;
    this.batchSize = batchSize;
  }

  @Scheduled(
      initialDelayString = "${payments.recovery.interval-ms:30000}",
      fixedDelayString = "${payments.recovery.interval-ms:30000}")
  public void scheduled() {
    recoverStale();
  }

  /** One recovery pass; returns the number of payments it finished. */
  public int recoverStale() {
    int recovered = 0;
    while (recovered < batchSize) {
      try {
        if (tx.execute(s -> recoverNext()).isEmpty()) {
          break;
        }
      } catch (CompensationRefused e) {
        // The failed transaction rolled back; record the outcome in a fresh one.
        log.error("recovery of payment {} needs an operator: {}", e.paymentId, e.getMessage());
        tx.executeWithoutResult(s -> markCompensationFailed(e.paymentId, e.getMessage()));
      } catch (RuntimeException e) {
        // Infrastructure trouble (DB, lock conflict): stop this pass and retry on the next tick.
        log.warn("saga recovery pass aborted; will retry", e);
        break;
      }
      recovered++;
    }
    tx.executeWithoutResult(s -> completeOrphanKeys());
    return recovered;
  }

  /** Claims and compensates the oldest stale in-flight payment, if any. */
  private Optional<Payment> recoverNext() {
    Optional<Payment> claimed =
        jdbc.sql(
                "SELECT * FROM payments WHERE status IN " + PaymentStore.IN_FLIGHT
                    + " AND updated_at < now() - (? * interval '1 millisecond')"
                    + " ORDER BY updated_at LIMIT 1 FOR UPDATE SKIP LOCKED")
            .param(timeout.toMillis())
            .query(PaymentStore.MAPPER)
            .optional();
    return claimed.map(this::compensate);
  }

  /**
   * Undoes whatever the dead saga did. Idempotent: an already-released hold is a no-op and an
   * already-reversed entry is skipped. Holds are found by reference, so a hold whose id the saga
   * never persisted is still released.
   */
  private Payment compensate(Payment p) {
    String ref = PaymentSaga.holdReference(p.id());
    boolean undid = false;
    for (HoldView h : ledger.holds(ref, p.holdId())) {
      undid |= ledger.releaseHold(h.id());
    }
    Long holdId = p.holdId();
    Long entryId = p.entryId();
    // Re-read: a hold that could not be released was captured, so its transfer must be reversed.
    for (HoldView h : ledger.holds(ref, p.holdId())) {
      holdId = h.id();
      Long entry = h.entryId() != null ? h.entryId() : p.entryId();
      if (!"CAPTURED".equals(h.status()) || entry == null) {
        continue;
      }
      entryId = entry;
      if (!ledger.isReversed(entry)) {
        try {
          ledger.reverse(entry);
        } catch (DomainException e) {
          throw new CompensationRefused(p.id(), "reversal of entry " + entry + " failed: "
              + e.code() + ": " + e.getMessage(), e);
        }
      }
      undid = true;
    }
    Payment.Status status = undid ? Payment.Status.COMPENSATED : Payment.Status.FAILED;
    String reason = "recovered: saga made no progress for " + timeout + " (was " + p.status() + ")";
    Payment result = store.finish(p, status, holdId, entryId, reason);
    completeKey(result);
    meters.counter("bank.saga.recovered", "outcome", result.status().name()).increment();
    log.warn("recovered stale payment {}: {} -> {}", p.id(), p.status(), result.status());
    return result;
  }

  private void markCompensationFailed(UUID id, String reason) {
    jdbc.sql("SELECT * FROM payments WHERE id = ? AND status IN " + PaymentStore.IN_FLIGHT
            + " FOR UPDATE SKIP LOCKED")
        .param(id)
        .query(PaymentStore.MAPPER)
        .optional()
        .ifPresent(p -> {
          Payment result = store.finish(p, Payment.Status.COMPENSATION_FAILED, p.holdId(),
              p.entryId(), "recovered: " + reason);
          completeKey(result);
          meters.counter("bank.saga.recovered", "outcome", result.status().name()).increment();
        });
  }

  /**
   * Keys left IN_PROGRESS by a crash after the saga finished but before the response was stored
   * get the stored payment as their response. Keys that never got linked to a payment (crash
   * before the saga started, so no money moved) are deleted, so a retry simply runs again.
   */
  private void completeOrphanKeys() {
    String stale = " AND k.created_at < now() - (? * interval '1 millisecond')";
    List<Payment> finished =
        jdbc.sql(
                "SELECT p.* FROM payments p JOIN idempotency_keys k ON k.payment_id = p.id"
                    + " WHERE k.status = 'IN_PROGRESS' AND p.status NOT IN " + PaymentStore.IN_FLIGHT
                    + stale + " LIMIT ?")
            .params(timeout.toMillis(), batchSize)
            .query(PaymentStore.MAPPER)
            .list();
    finished.forEach(this::completeKey);
    jdbc.sql("DELETE FROM idempotency_keys k WHERE k.status = 'IN_PROGRESS' AND k.payment_id IS NULL"
            + stale)
        .param(timeout.toMillis())
        .update();
  }

  /** Stores the response the controller would have sent; a no-op if the key is already done. */
  private void completeKey(Payment p) {
    int status = p.status() == Payment.Status.COMPLETED ? 201 : 422;
    String body;
    try {
      body = json.writeValueAsString(p);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    jdbc.sql(
            "UPDATE idempotency_keys SET status = 'COMPLETED', response_status = ?,"
                + " response_body = ? WHERE payment_id = ? AND status = 'IN_PROGRESS'")
        .params(status, body, p.id())
        .update();
  }
}
