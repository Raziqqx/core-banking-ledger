package com.corebank.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/** Own context with a tiny limit: 3 payments per hour per client. */
@TestPropertySource(properties = {"ratelimit.payments.capacity=3", "ratelimit.payments.period=PT1H"})
class RateLimitIT extends IntegrationTestBase {

  private MvcResult pay(String bearer, long from, long to) throws Exception {
    return mvc.perform(postJson("/payments", Map.of("fromAccount", from, "toAccount", to,
            "amount", 1, "currency", "SGD"))
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .header("Authorization", bearer)).andReturn();
  }

  @Test
  void fourthPaymentWithinTheWindowIs429WithRetryAfterAndOtherClientsAreUnaffected()
      throws Exception {
    long a = openAccount("rl-alice", "SGD", 1_000, 0);
    long b = openAccount("rl-bob", "SGD", 1_000, 0);
    String alice = bearer("rl-alice", "CUSTOMER");

    for (int i = 0; i < 3; i++) {
      assertThat(pay(alice, a, b).getResponse().getStatus()).isEqualTo(201);
    }
    MvcResult limited = pay(alice, a, b);
    assertThat(limited.getResponse().getStatus()).isEqualTo(429);
    assertThat(limited.getResponse().getContentType()).isEqualTo("application/problem+json");
    // One token refills every 20 minutes at 3/hour.
    long retryAfter = Long.parseLong(limited.getResponse().getHeader("Retry-After"));
    assertThat(retryAfter).isBetween(1L, 20L * 60);
    assertThat(json.readTree(limited.getResponse().getContentAsString()).get("code").asText())
        .isEqualTo("RATE_LIMITED");
    assertThat(balance(a).get("balance").asLong()).isEqualTo(997); // the 4th never ran

    // Buckets are per client: bob still has his full allowance.
    assertThat(pay(bearer("rl-bob", "CUSTOMER"), b, a).getResponse().getStatus()).isEqualTo(201);
    // Reads are not rate limited.
    call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
        .get("/accounts/" + a).header("Authorization", alice), 200);
  }
}
