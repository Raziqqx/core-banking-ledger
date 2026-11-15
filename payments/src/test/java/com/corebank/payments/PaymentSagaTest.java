package com.corebank.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.corebank.common.DomainException;
import com.corebank.ledger.LedgerService;
import com.corebank.payments.Payment.Status;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class PaymentSagaTest {
  private final LedgerService ledger = mock(LedgerService.class);
  private final PaymentStore store = mock(PaymentStore.class);
  private final PaymentSaga saga = new PaymentSaga(ledger, store);
  private final Payment p =
      new Payment(UUID.randomUUID(), 1, 2, 500, "SGD", null, Status.PENDING, null, null, null,
          Instant.now());

  @BeforeEach
  void stubs() {
    when(store.finish(any(), any(), any(), any(), any())).thenReturn(p);
    when(store.get(p.id())).thenReturn(p);
  }

  @Test
  void happyPathRunsAllStepsAndCompensatesNothing() {
    when(ledger.placeHold(1, 500, "SGD")).thenReturn(10L);
    when(ledger.captureHold(eq(10L), eq(2L), any())).thenReturn(20L);

    saga.run(p);

    verify(store).finish(p, Status.COMPLETED, 10L, 20L, null);
    verify(ledger, never()).releaseHold(anyLong());
    verify(ledger, never()).reverse(anyLong());
  }

  @Test
  void reserveFailureEndsFailedWithNothingToUndo() {
    when(ledger.placeHold(1, 500, "SGD")).thenThrow(DomainException.insufficientFunds(1));

    saga.run(p);

    verify(store).finish(eq(p), eq(Status.FAILED), isNull(), isNull(), any());
    verify(ledger, never()).captureHold(anyLong(), anyLong(), any());
    verify(ledger, never()).releaseHold(anyLong());
  }

  @Test
  void postFailureReleasesTheHold() {
    when(ledger.placeHold(1, 500, "SGD")).thenReturn(10L);
    when(ledger.captureHold(eq(10L), eq(2L), any()))
        .thenThrow(DomainException.invalid("currency mismatch"));

    saga.run(p);

    verify(ledger).releaseHold(10L);
    verify(ledger, never()).reverse(anyLong());
    verify(store).finish(eq(p), eq(Status.COMPENSATED), eq(10L), isNull(), any());
  }

  @Test
  void notifyFailureReversesTheEntryThenReleasesInReverseOrder() {
    when(ledger.placeHold(1, 500, "SGD")).thenReturn(10L);
    when(ledger.captureHold(eq(10L), eq(2L), any())).thenReturn(20L);
    when(store.finish(p, Status.COMPLETED, 10L, 20L, null))
        .thenThrow(new IllegalStateException("outbox write failed"));

    saga.run(p);

    InOrder order = inOrder(ledger, store);
    order.verify(ledger).reverse(20L);
    order.verify(ledger).releaseHold(10L);
    order.verify(store).finish(eq(p), eq(Status.COMPENSATED), eq(10L), eq(20L), any());
  }

  @Test
  void failedCompensationIsFlaggedForOperators() {
    when(ledger.placeHold(1, 500, "SGD")).thenReturn(10L);
    when(ledger.captureHold(eq(10L), eq(2L), any())).thenThrow(new IllegalStateException("boom"));
    when(ledger.releaseHold(10L)).thenThrow(new IllegalStateException("db down"));

    saga.run(p);

    verify(store).finish(eq(p), eq(Status.COMPENSATION_FAILED), eq(10L), isNull(), any());
  }

  @Test
  void requestHashIsStableAndBodySensitive() {
    var svc = new IdempotencyService(null, new com.fasterxml.jackson.databind.ObjectMapper());
    var a = new PaymentRequest(1L, 2L, 500, "SGD", "rent");
    assertThat(svc.hash(a)).isEqualTo(svc.hash(new PaymentRequest(1L, 2L, 500, "SGD", "rent")));
    assertThat(svc.hash(a)).isNotEqualTo(svc.hash(new PaymentRequest(1L, 2L, 501, "SGD", "rent")));
  }
}
