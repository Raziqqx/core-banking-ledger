package com.corebank.app.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PaymentRateLimiterTest {
  @Test
  void allowsCapacityThenReportsWaitRoundedUp() {
    // A long period so slow class loading on a busy CI box cannot shift the expected wait
    // (with a 10 s period this flaked: refill had already progressed by 2 s).
    PaymentRateLimiter limiter = new PaymentRateLimiter(2, Duration.ofHours(1));
    assertThat(limiter.tryConsume("a")).isZero();
    assertThat(limiter.tryConsume("a")).isZero();
    // Greedy refill: one token every 1800 s.
    assertThat(limiter.tryConsume("a")).isBetween(1740L, 1800L);
  }

  @Test
  void bucketsAreIndependentPerClient() {
    PaymentRateLimiter limiter = new PaymentRateLimiter(1, Duration.ofMinutes(1));
    assertThat(limiter.tryConsume("a")).isZero();
    assertThat(limiter.tryConsume("a")).isPositive();
    assertThat(limiter.tryConsume("b")).isZero();
  }
}
