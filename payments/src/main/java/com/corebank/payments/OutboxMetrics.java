package com.corebank.payments;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * Outbox lag gauges, read from the database at scrape time so they stay correct with several relay
 * instances (or none): {@code bank.outbox.pending} is the unpublished row count and
 * {@code bank.outbox.lag} is the age in seconds of the oldest unpublished row. A lag that keeps
 * growing means the relay or the broker is stuck, which an alert should catch.
 */
@Component
public class OutboxMetrics implements MeterBinder {
  private final OutboxRepository outbox;

  public OutboxMetrics(OutboxRepository outbox) {
    this.outbox = outbox;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("bank.outbox.pending", outbox, o -> o.backlog().pending())
        .description("Unpublished outbox rows")
        .register(registry);
    Gauge.builder("bank.outbox.lag", outbox, o -> o.backlog().oldestAgeSeconds())
        .description("Age of the oldest unpublished outbox row")
        .baseUnit("seconds")
        .register(registry);
  }
}
