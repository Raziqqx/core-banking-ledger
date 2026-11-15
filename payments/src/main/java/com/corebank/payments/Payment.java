package com.corebank.payments;

import java.time.Instant;
import java.util.UUID;

/** A payment and how far its saga got. {@code holdId}/{@code entryId} are null until reached. */
public record Payment(
    UUID id,
    long fromAccount,
    long toAccount,
    long amount,
    String currency,
    String reference,
    Status status,
    Long holdId,
    Long entryId,
    String failureReason,
    Instant createdAt) {

  public enum Status {
    PENDING,
    RESERVED,
    POSTED,
    COMPLETED,
    /** Failed before any side effect; nothing to undo. */
    FAILED,
    /** Failed after a side effect; every completed step was undone. */
    COMPENSATED,
    /** A compensation itself failed; needs an operator (or a recovery job). */
    COMPENSATION_FAILED
  }
}
