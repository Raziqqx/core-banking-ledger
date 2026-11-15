package com.corebank.payments;

import com.corebank.common.DomainException;
import com.corebank.ledger.LedgerService;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrated saga: reserve -> post -> notify. Each step is its own local transaction on a
 * participant, as it would be across services. If a step fails, the completed steps are undone in
 * reverse order (release the hold, reverse the journal entry), and the payment ends FAILED or
 * COMPENSATED with a PaymentFailed event.
 */
@Service
public class PaymentSaga {
  private static final Logger log = LoggerFactory.getLogger(PaymentSaga.class);

  private final LedgerService ledger;
  private final PaymentStore store;

  public PaymentSaga(LedgerService ledger, PaymentStore store) {
    this.ledger = ledger;
    this.store = store;
  }

  private record Step(String name, Runnable action, Runnable compensation) {}

  /** Mutable saga state shared by the steps. */
  private static final class Context {
    Long holdId;
    Long entryId;
  }

  public Payment run(Payment p) {
    Context ctx = new Context();
    String desc = "payment " + p.id();
    List<Step> steps =
        List.of(
            new Step(
                "reserve",
                () -> {
                  ctx.holdId = ledger.placeHold(p.fromAccount(), p.amount(), p.currency());
                  store.progress(p.id(), Payment.Status.RESERVED, ctx.holdId, null);
                },
                () -> ledger.releaseHold(ctx.holdId)),
            new Step(
                "post",
                () -> {
                  ctx.entryId = ledger.captureHold(ctx.holdId, p.toAccount(), desc);
                  store.progress(p.id(), Payment.Status.POSTED, ctx.holdId, ctx.entryId);
                },
                () -> ledger.reverse(ctx.entryId)),
            new Step(
                "notify",
                // The outbox write commits with the COMPLETED status; the relay delivers it later.
                () -> store.finish(p, Payment.Status.COMPLETED, ctx.holdId, ctx.entryId, null),
                () -> {}));

    Deque<Step> done = new ArrayDeque<>();
    for (Step step : steps) {
      try {
        step.action().run();
        done.push(step);
      } catch (RuntimeException e) {
        String reason = step.name() + " failed: " + describe(e);
        log.warn("payment {} {}; compensating {} step(s)", p.id(), reason, done.size());
        return compensate(p, ctx, done, reason);
      }
    }
    return store.get(p.id());
  }

  private Payment compensate(Payment p, Context ctx, Deque<Step> done, String reason) {
    boolean undidSomething = !done.isEmpty();
    while (!done.isEmpty()) {
      Step step = done.pop();
      try {
        step.compensation().run();
      } catch (RuntimeException e) {
        log.error("payment {} compensation of {} failed", p.id(), step.name(), e);
        return store.finish(p, Payment.Status.COMPENSATION_FAILED, ctx.holdId, ctx.entryId,
            reason + "; compensation of " + step.name() + " failed: " + describe(e));
      }
    }
    Payment.Status status = undidSomething ? Payment.Status.COMPENSATED : Payment.Status.FAILED;
    return store.finish(p, status, ctx.holdId, ctx.entryId, reason);
  }

  private static String describe(RuntimeException e) {
    return e instanceof DomainException d ? d.code() + ": " + d.getMessage() : e.toString();
  }
}
