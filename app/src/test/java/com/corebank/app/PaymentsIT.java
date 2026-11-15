package com.corebank.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class PaymentsIT extends IntegrationTestBase {
  @Autowired JdbcClient jdbc;

  private MockHttpServletRequestBuilder pay(String key, long from, long to, long amount, String ccy)
      throws Exception {
    return postJson("/payments", Map.of("fromAccount", from, "toAccount", to, "amount", amount,
            "currency", ccy, "reference", "invoice-42"))
        .header("Idempotency-Key", key)
        .header("Authorization", adminBearer());
  }

  private long count(String sql, Object... params) {
    return jdbc.sql(sql).params(params).query(Long.class).single();
  }

  @Test
  void completedPaymentMovesMoneyAndQueuesAnEvent() throws Exception {
    long a = openAccount("SGD", 10_000, 0);
    long b = openAccount("SGD", 0, 0);

    JsonNode p = call(pay(UUID.randomUUID().toString(), a, b, 4_000, "SGD"), 201);

    assertThat(p.get("status").asText()).isEqualTo("COMPLETED");
    assertThat(balance(a).get("balance").asLong()).isEqualTo(6_000);
    assertThat(balance(a).get("held").asLong()).isZero();
    assertThat(balance(b).get("balance").asLong()).isEqualTo(4_000);
    assertThat(count("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND type = 'PaymentCompleted'",
        p.get("id").asText())).isEqualTo(1);
    assertThat(call(get("/payments/" + p.get("id").asText()), 200).get("entryId").isNumber()).isTrue();
  }

  @Test
  void sameKeySameBodyReplaysTheIdenticalResponseAndPaysOnce() throws Exception {
    long a = openAccount("SGD", 10_000, 0);
    long b = openAccount("SGD", 0, 0);
    String key = UUID.randomUUID().toString();

    MvcResult first = mvc.perform(pay(key, a, b, 1_000, "SGD")).andReturn();
    MvcResult retry = mvc.perform(pay(key, a, b, 1_000, "SGD")).andReturn();

    assertThat(retry.getResponse().getStatus()).isEqualTo(first.getResponse().getStatus()).isEqualTo(201);
    assertThat(retry.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
    assertThat(balance(a).get("balance").asLong()).isEqualTo(9_000);
    assertThat(count("SELECT count(*) FROM payments WHERE from_account = ?", a)).isEqualTo(1);
  }

  @Test
  void sameKeyDifferentBodyIsA409() throws Exception {
    long a = openAccount("SGD", 10_000, 0);
    long b = openAccount("SGD", 0, 0);
    String key = UUID.randomUUID().toString();

    call(pay(key, a, b, 1_000, "SGD"), 201);
    JsonNode err = call(pay(key, a, b, 2_000, "SGD"), 409);

    assertThat(err.get("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(balance(a).get("balance").asLong()).isEqualTo(9_000);
  }

  @Test
  void failedPaymentResultIsAlsoReplayed() throws Exception {
    long a = openAccount("SGD", 500, 0);
    long b = openAccount("SGD", 0, 0);
    String key = UUID.randomUUID().toString();

    JsonNode p = call(pay(key, a, b, 1_000, "SGD"), 422);
    assertThat(p.get("status").asText()).isEqualTo("FAILED");
    assertThat(p.get("failureReason").asText()).contains("INSUFFICIENT_FUNDS");
    // Topping up does not change the answer for the same key: the stored result is returned.
    call(postJson("/accounts/" + a + "/deposits", Map.of("amount", 5_000)), 201);
    assertThat(call(pay(key, a, b, 1_000, "SGD"), 422).get("id")).isEqualTo(p.get("id"));
  }

  @Test
  void postStepFailureCompensatesByReleasingTheHold() throws Exception {
    long sgd = openAccount("SGD", 10_000, 0);
    long usd = openAccount("USD", 0, 0); // capture fails: currency mismatch on the credit side

    JsonNode p = call(pay(UUID.randomUUID().toString(), sgd, usd, 3_000, "SGD"), 422);

    assertThat(p.get("status").asText()).isEqualTo("COMPENSATED");
    assertThat(p.get("failureReason").asText()).startsWith("post failed");
    JsonNode bal = balance(sgd);
    assertThat(bal.get("balance").asLong()).isEqualTo(10_000);
    assertThat(bal.get("held").asLong()).isZero();
    assertThat(count("SELECT count(*) FROM holds WHERE id = ? AND status = 'RELEASED'",
        p.get("holdId").asLong())).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND type = 'PaymentFailed'",
        p.get("id").asText())).isEqualTo(1);
  }

  @Test
  void missingIdempotencyKeyIsA400() throws Exception {
    long a = openAccount("SGD", 1_000, 0);
    call(postJson("/payments", Map.of("fromAccount", a, "toAccount", a + 1, "amount", 1,
        "currency", "SGD")), 400);
  }

  /** Concurrent retries with one key: exactly one execution, every caller sees it or a 409. */
  @Test
  void concurrentRetriesWithOneKeyExecuteExactlyOnce() throws Exception {
    long a = openAccount("SGD", 10_000, 0);
    long b = openAccount("SGD", 0, 0);
    String key = UUID.randomUUID().toString();
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<Future<Integer>> results = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      Callable<Integer> c = () -> mvc.perform(pay(key, a, b, 700, "SGD")).andReturn().getResponse().getStatus();
      results.add(pool.submit(c));
    }
    List<Integer> statuses = new ArrayList<>();
    for (Future<Integer> f : results) {
      statuses.add(f.get());
    }
    pool.shutdown();

    assertThat(statuses).containsOnly(201, 409).contains(201);
    assertThat(count("SELECT count(*) FROM payments WHERE from_account = ?", a)).isEqualTo(1);
    assertThat(balance(a).get("balance").asLong()).isEqualTo(9_300);
  }
}
