package com.corebank.payments;

import com.corebank.common.DomainException;
import com.corebank.common.PaymentEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persists payments and saga progress. Terminal transitions also write the outbox event. */
@Repository
public class PaymentStore {
  private static final RowMapper<Payment> MAPPER =
      (rs, i) ->
          new Payment(
              rs.getObject("id", UUID.class),
              rs.getLong("from_account"),
              rs.getLong("to_account"),
              rs.getLong("amount"),
              rs.getString("currency"),
              rs.getString("reference"),
              Payment.Status.valueOf(rs.getString("status")),
              (Long) rs.getObject("hold_id"),
              (Long) rs.getObject("entry_id"),
              rs.getString("failure_reason"),
              rs.getTimestamp("created_at").toInstant());

  private final JdbcClient jdbc;
  private final OutboxRepository outbox;

  public PaymentStore(JdbcClient jdbc, OutboxRepository outbox) {
    this.jdbc = jdbc;
    this.outbox = outbox;
  }

  public Payment create(PaymentRequest r) {
    return jdbc.sql(
            "INSERT INTO payments(id, from_account, to_account, amount, currency, reference, status)"
                + " VALUES (?, ?, ?, ?, ?, ?, 'PENDING') RETURNING *")
        .params(UUID.randomUUID(), r.fromAccount(), r.toAccount(), r.amount(), r.currency(),
            r.reference())
        .query(MAPPER)
        .single();
  }

  public Payment get(UUID id) {
    return jdbc.sql("SELECT * FROM payments WHERE id = ?")
        .param(id)
        .query(MAPPER)
        .optional()
        .orElseThrow(() -> DomainException.notFound("payment " + id));
  }

  /** Records a non-terminal step so a crashed saga's progress is visible. */
  public void progress(UUID id, Payment.Status status, Long holdId, Long entryId) {
    jdbc.sql("UPDATE payments SET status = ?, hold_id = ?, entry_id = ?, updated_at = now() WHERE id = ?")
        .params(status.name(), holdId, entryId, id)
        .update();
  }

  /** Terminal transition and its event, committed together (the outbox guarantee). */
  @Transactional
  public Payment finish(Payment p, Payment.Status status, Long holdId, Long entryId, String reason) {
    jdbc.sql(
            "UPDATE payments SET status = ?, hold_id = ?, entry_id = ?, failure_reason = ?,"
                + " updated_at = now() WHERE id = ?")
        .params(status.name(), holdId, entryId, reason, p.id())
        .update();
    if (status != Payment.Status.COMPENSATION_FAILED) {
      String type =
          status == Payment.Status.COMPLETED ? PaymentEvent.COMPLETED : PaymentEvent.FAILED;
      outbox.add(
          new PaymentEvent(UUID.randomUUID(), type, p.id(), p.fromAccount(), p.toAccount(),
              p.amount(), p.currency(), reason, Instant.now()));
    }
    return get(p.id());
  }
}
