package com.corebank.payments;

import com.corebank.common.DomainException;
import com.corebank.ledger.LedgerService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrated saga: reserve -> post -> notify. Each step is its own local transaction on a
 * participant, as it would be across services. If a step fails, the completed steps are undone in
 * reverse order (release the hold, reverse the journal entry), and the payment ends FAILED or
 * COMPENSATED with a PaymentFailed event. A saga that dies mid-run (process crash) is finished
 * by {@link SagaRecovery}.
 *
 * <p>Metrics: {@code bank.payments{outcome}} counts finished sagas by final status and
 * {@code bank.saga.duration{outcome}} times them (with a percentile histogram for Prometheus).
 */
@Service
public class PaymentSaga {
  private static final Logger log = LoggerFactory.getLogger(PaymentSaga.class);

  private final LedgerService ledger;
  private final PaymentStore store;
  private final MeterRegistry meters;

  public PaymentSaga(LedgerService ledger, PaymentStore store, MeterRegistry meters) {
    this.ledger = ledger;
    this.store = store;
    this.meters = meters;
  }

  /**
   * One saga step. {@code effect} is the participant call (the side effect to undo on failure);
   * {@code record} persists its result. The step's compensation is registered as soon as
   * {@code effect} returns, before {@code record} runs, so a failure while recording still undoes
   * the side effect.
   */
  private record Step(String name, Runnable effect, Runnable record, Runnable compensation) {}

  /** Hold reference used by the saga and by {@link SagaRecovery} to find a payment's holds. */
  static String holdReference(UUID paymentId) {
    return "payment:" + paymentId;
  }

  private static final class Context {
    Long holdId;
    Long entryId;
    /** The final status this saga decided on; used as the metrics outcome tag. */
    Payment.Status outcome;
  }

  public Payment run(Payment p) {
    Context ctx = new Context();
    Timer.Sample sample = Timer.start(meters);
    try {
      return execute(p, ctx);
    } finally {
      // An unexpected exception (e.g. the DB is down while finishing) is tagged ERROR.
      String outcome = ctx.outcome == null ? "ERROR" : ctx.outcome.name();
      sample.stop(Timer.builder("bank.saga.duration")
          .description("End-to-end payment saga duration")
          .tag("outcome", outcome)
          .publishPercentileHistogram()
          .register(meters));
      meters.counter("bank.payments", "outcome", outcome).increment();
    }
  }

  private Payment execute(Payment p, Context ctx) {
    String desc = "payment " + p.id();
    List<Step> steps =
        List.of(
            new Step(
                "reserve",
                () ->
                    ctx.holdId =
                        ledger.placeHold(
                            p.fromAccount(), p.amount(), p.currency(), holdReference(p.id())),
                () -> store.progress(p.id(), Payment.Status.RESERVED, ctx.holdId, null),
                () -> ledger.releaseHold(ctx.holdId)),
            new Step(
                "post",
                () -> ctx.entryId = ledger.captureHold(ctx.holdId, p.toAccount(), desc),
                () -> store.progress(p.id(), Payment.Status.POSTED, ctx.holdId, ctx.entryId),
                () -> ledger.reverse(ctx.entryId)),
            new Step(
                "notify",
                // The outbox write commits with the COMPLETED status; the relay delivers it later.
                () -> store.finish(p, Payment.Status.COMPLETED, ctx.holdId, ctx.entryId, null),
                () -> {},
                () -> {}));

    Deque<Step> done = new ArrayDeque<>();
    for (Step step : steps) {
      try {
        step.effect().run();
        done.push(step); // the side effect happened: from here on it must be undone on failure
        step.record().run();
      } catch (RuntimeException e) {
        String reason = step.name() + " failed: " + describe(e);
        log.warn("payment {} {}; compensating {} step(s)", p.id(), reason, done.size());
        return compensate(p, ctx, done, reason);
      }
    }
    Payment completed = store.get(p.id());
    ctx.outcome = Payment.Status.COMPLETED;
    return completed;
  }

  private Payment compensate(Payment p, Context ctx, Deque<Step> done, String reason) {
    boolean undidSomething = !done.isEmpty();
    while (!done.isEmpty()) {
      Step step = done.pop();
      try {
        step.compensation().run();
      } catch (RuntimeException e) {
        log.error("payment {} compensation of {} failed", p.id(), step.name(), e);
        ctx.outcome = Payment.Status.COMPENSATION_FAILED;
        return store.finish(p, Payment.Status.COMPENSATION_FAILED, ctx.holdId, ctx.entryId,
            reason + "; compensation of " + step.name() + " failed: " + describe(e));
      }
    }
    Payment.Status status = undidSomething ? Payment.Status.COMPENSATED : Payment.Status.FAILED;
    ctx.outcome = status;
    return store.finish(p, status, ctx.holdId, ctx.entryId, reason);
  }

  private static String describe(RuntimeException e) {
    return e instanceof DomainException d ? d.code() + ": " + d.getMessage() : e.toString();
  }
}
