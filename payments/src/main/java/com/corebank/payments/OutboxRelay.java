package com.corebank.payments;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polls the outbox and publishes to Kafka. Delivery is at least once: if the process dies between
 * the broker ack and the commit that marks rows published, they are sent again, so consumers must
 * de-duplicate on the event id. Records are keyed by payment id, which keeps one payment's
 * events in order on a single partition.
 */
@Configuration
@ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {
  private final OutboxRepository outbox;
  private final KafkaTemplate<String, String> kafka;
  private final String topic;

  public OutboxRelay(
      OutboxRepository outbox,
      KafkaTemplate<String, String> kafka,
      @Value("${payments.topic:payment-events}") String topic) {
    this.outbox = outbox;
    this.kafka = kafka;
    this.topic = topic;
  }

  @Bean
  NewTopic paymentEventsTopic() {
    return TopicBuilder.name(topic).partitions(3).replicas(1).build();
  }

  @Scheduled(fixedDelayString = "${outbox.relay.interval-ms:200}")
  @Transactional
  public void relay() throws Exception {
    List<OutboxRepository.Row> batch = outbox.lockBatch(100);
    if (batch.isEmpty()) {
      return;
    }
    CompletableFuture<?>[] sends =
        batch.stream()
            .map(r -> kafka.send(topic, r.key(), r.payload()))
            .toArray(CompletableFuture[]::new);
    // Any failed send throws here, rolling back so the whole batch is retried on the next tick.
    CompletableFuture.allOf(sends).get(10, TimeUnit.SECONDS);
    outbox.markPublished(batch.stream().map(OutboxRepository.Row::id).toList());
  }
}
