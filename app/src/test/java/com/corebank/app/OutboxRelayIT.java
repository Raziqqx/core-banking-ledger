package com.corebank.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.corebank.common.PaymentEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;

/** Outbox -> relay -> Redpanda -> notifications consumer, end to end on real containers. */
class OutboxRelayIT extends IntegrationTestBase {
  @Autowired JdbcClient jdbc;
  @Autowired KafkaTemplate<String, String> kafka;

  private long notificationsFor(UUID paymentId) {
    return jdbc.sql("SELECT count(*) FROM notifications WHERE payment_id = ?")
        .param(paymentId)
        .query(Long.class)
        .single();
  }

  @Test
  void paymentEventIsRelayedAndConsumedOnce() throws Exception {
    long a = openAccount("SGD", 10_000, 0);
    long b = openAccount("SGD", 0, 0);
    JsonNode p =
        call(postJson("/payments", Map.of("fromAccount", a, "toAccount", b, "amount", 1_234,
                "currency", "SGD")).header("Idempotency-Key", UUID.randomUUID().toString()), 201);
    UUID id = UUID.fromString(p.get("id").asText());

    await().atMost(Duration.ofSeconds(30)).until(() -> notificationsFor(id) == 1);

    assertThat(jdbc.sql("SELECT published_at IS NOT NULL FROM outbox WHERE aggregate_id = ?")
        .param(id.toString()).query(Boolean.class).single()).isTrue();
    assertThat(jdbc.sql("SELECT message FROM notifications WHERE payment_id = ?")
        .param(id).query(String.class).single())
        .isEqualTo("Payment of 12.34 SGD to account " + b + " completed");
  }

  @Test
  void redeliveredEventProducesOneNotification() throws Exception {
    PaymentEvent e = new PaymentEvent(UUID.randomUUID(), PaymentEvent.COMPLETED, UUID.randomUUID(),
        1, 2, 100, "SGD", null, Instant.now());
    String payload = json.writeValueAsString(e);
    UUID sentinel = UUID.randomUUID();
    String marker = json.writeValueAsString(new PaymentEvent(UUID.randomUUID(), PaymentEvent.COMPLETED,
        sentinel, 1, 2, 1, "SGD", null, Instant.now()));

    // Same key -> same partition -> consumed in order, so once the marker lands both copies of e
    // have been processed.
    String key = e.paymentId().toString();
    kafka.send("payment-events", key, payload).get();
    kafka.send("payment-events", key, payload).get();
    kafka.send("payment-events", key, marker).get();

    await().atMost(Duration.ofSeconds(30)).until(() -> notificationsFor(sentinel) == 1);
    assertThat(notificationsFor(e.paymentId())).isEqualTo(1);
  }
}
