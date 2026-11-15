package com.corebank.app.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PaymentRateLimiterTest {
  @Test
  void allowsCapacityThenReportsWaitRoundedUp() {
    PaymentRateLimiter limiter = new PaymentRateLimiter(2, Duration.ofSeconds(10));
    assertThat(limiter.tryConsume("a")).isZero();
    assertThat(limiter.tryConsume("a")).isZero();
    // Greedy refill: one token every 5 s.
    assertThat(limiter.tryConsume("a")).isBetween(4L, 5L);
  }

  @Test
  void bucketsAreIndependentPerClient() {
    PaymentRateLimiter limiter = new PaymentRateLimiter(1, Duration.ofMinutes(1));
    assertThat(limiter.tryConsume("a")).isZero();
    assertThat(limiter.tryConsume("a")).isPositive();
    assertThat(limiter.tryConsume("b")).isZero();
  }
}
