package com.corebank.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;

/**
 * The Prometheus scrape endpoint is public (no token) and exposes the custom payment, saga and
 * outbox metrics. Spring Boot tests switch metrics export off unless asked, hence the annotation
 * (tracing stays off: there is no collector in tests).
 */
@AutoConfigureObservability(tracing = false)
class ObservabilityIT extends IntegrationTestBase {

  @Test
  void prometheusEndpointExposesCustomMetricsWithoutAToken() throws Exception {
    long a = openAccount("SGD", 10_000, 0);
    long b = openAccount("SGD", 0, 0);
    call(postJson("/payments", Map.of("fromAccount", a, "toAccount", b, "amount", 100,
        "currency", "SGD")).header("Idempotency-Key", UUID.randomUUID().toString()), 201);

    String scrape = mvc.perform(get("/actuator/prometheus")).andReturn().getResponse()
        .getContentAsString();

    assertThat(scrape)
        .contains("bank_payments_total{application=\"core-banking-ledger\",outcome=\"COMPLETED\"}")
        .contains("bank_saga_duration_seconds_bucket")
        .contains("bank_outbox_lag_seconds")
        .contains("bank_outbox_pending");
  }

  @Test
  void healthProbesArePublic() throws Exception {
    assertThat(mvc.perform(get("/actuator/health/readiness")).andReturn().getResponse().getStatus())
        .isEqualTo(200);
  }
}
