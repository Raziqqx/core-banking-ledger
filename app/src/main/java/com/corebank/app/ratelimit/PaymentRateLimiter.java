package com.corebank.app.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Token bucket per client for payment creation: {@code capacity} requests per {@code period},
 * refilled greedily (smoothly). Buckets live in-process in a bounded cache, so the limit is per
 * instance; a cluster-wide limit would back Bucket4j with Redis or Postgres instead.
 */
@Component
public class PaymentRateLimiter {
  private final long capacity;
  private final Duration period;
  // An idle bucket refills to full within one period, so evicting it after a few idle periods
  // cannot let a client exceed the limit.
  private final Cache<String, Bucket> buckets;

  public PaymentRateLimiter(
      @Value("${ratelimit.payments.capacity:30}") long capacity,
      @Value("${ratelimit.payments.period:PT1M}") Duration period) {
    this.capacity = capacity;
    this.period = period;
    this.buckets =
        Caffeine.newBuilder().maximumSize(100_000).expireAfterAccess(period.multipliedBy(5)).build();
  }

  /** Takes one token; returns 0 when allowed, otherwise whole seconds until a token is free. */
  public long tryConsume(String client) {
    ConsumptionProbe probe = buckets.get(client, k -> newBucket()).tryConsumeAndReturnRemaining(1);
    if (probe.isConsumed()) {
      return 0;
    }
    long nanos = probe.getNanosToWaitForRefill();
    return Math.max(1, (nanos + 999_999_999L) / 1_000_000_000L); // round up to whole seconds
  }

  private Bucket newBucket() {
    return Bucket.builder()
        .addLimit(limit -> limit.capacity(capacity).refillGreedy(capacity, period))
        .build();
  }
}
