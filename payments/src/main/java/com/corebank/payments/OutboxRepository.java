package com.corebank.payments;

import com.corebank.common.PaymentEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional outbox: events are inserted in the same transaction as the state change they
 * describe, so an event exists if and only if the change committed. {@link OutboxRelay} publishes.
 */
@Repository
public class OutboxRepository {
  public record Row(long id, String key, String payload) {}

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public OutboxRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /** Must join the caller's transaction: an outbox write outside one defeats the pattern. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void add(PaymentEvent event) {
    try {
      jdbc.sql(
              "INSERT INTO outbox(event_id, aggregate_id, type, payload) VALUES (?, ?, ?, ?::jsonb)")
          .params(event.eventId(), event.paymentId().toString(), event.type(),
              json.writeValueAsString(event))
          .update();
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Locks a batch of unpublished rows; SKIP LOCKED lets several relay instances run side by side. */
  public List<Row> lockBatch(int limit) {
    return jdbc.sql(
            "SELECT id, aggregate_id, payload::text FROM outbox WHERE published_at IS NULL"
                + " ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED")
        .param(limit)
        .query((rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3)))
        .list();
  }

  public void markPublished(List<Long> ids) {
    jdbc.sql("UPDATE outbox SET published_at = now() WHERE id = ANY(?)")
        .param(ids.toArray(Long[]::new))
        .update();
  }
}
