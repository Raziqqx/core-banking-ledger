package com.corebank.notifications;

import com.corebank.common.PaymentEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Idempotent consumer of payment events. The outbox relay delivers at least once, so the unique
 * event_id turns redelivery into a no-op: each event produces exactly one notification.
 */
@Component
public class NotificationListener {
  private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public NotificationListener(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @KafkaListener(topics = "${payments.topic:payment-events}", groupId = "notifications")
  public void onEvent(String payload) throws Exception {
    PaymentEvent e = json.readValue(payload, PaymentEvent.class);
    int inserted =
        jdbc.sql(
                "INSERT INTO notifications(event_id, payment_id, account_id, message)"
                    + " VALUES (?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING")
            .params(e.eventId(), e.paymentId(), e.fromAccount(), message(e))
            .update();
    if (inserted == 0) {
      log.info("duplicate event {} ignored", e.eventId());
    }
  }

  static String message(PaymentEvent e) {
    String amount = BigDecimal.valueOf(e.amountMinor(), 2).toPlainString() + " " + e.currency();
    return PaymentEvent.COMPLETED.equals(e.type())
        ? "Payment of " + amount + " to account " + e.toAccount() + " completed"
        : "Payment of " + amount + " to account " + e.toAccount() + " failed: " + e.reason();
  }
}
