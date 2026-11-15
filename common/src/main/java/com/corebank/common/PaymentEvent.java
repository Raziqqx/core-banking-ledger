package com.corebank.common;

import java.time.Instant;
import java.util.UUID;

/**
 * Event contract between the payments service (producer, via the outbox) and its consumers.
 * {@code eventId} is unique per event and is the consumers' de-duplication key, because the
 * outbox relay delivers at least once.
 */
public record PaymentEvent(
    UUID eventId,
    String type,
    UUID paymentId,
    long fromAccount,
    long toAccount,
    long amountMinor,
    String currency,
    String reason,
    Instant occurredAt) {

  public static final String COMPLETED = "PaymentCompleted";
  public static final String FAILED = "PaymentFailed";
}
