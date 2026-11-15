package com.corebank.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerIT extends IntegrationTestBase {
  @Autowired JdbcClient jdbc;

  private Map<String, Object> transfer(long from, long to, long amount) {
    return Map.of("fromAccount", from, "toAccount", to, "amount", amount);
  }

  @Test
  void transferMovesMoneyAndRecordsBothLegs() throws Exception {
    long a = openAccount("SGD", 10_000, 0);
    long b = openAccount("SGD", 0, 0);

    call(postJson("/transfers", transfer(a, b, 2_500)), 201);

    assertThat(balance(a).get("balance").asLong()).isEqualTo(7_500);
    assertThat(balance(b).get("balance").asLong()).isEqualTo(2_500);
    JsonNode history = call(get("/accounts/" + a + "/history"), 200);
    assertThat(history).hasSize(2); // deposit + transfer, newest first
    assertThat(history.get(0).get("amount").asLong()).isEqualTo(-2_500);
    assertThat(call(get("/ledger/audit"), 200).get("ok").asBoolean()).isTrue();
  }

  @Test
  void overdraftIsRejectedAndNothingIsWritten() throws Exception {
    long a = openAccount("SGD", 1_000, 0);
    long b = openAccount("SGD", 0, 0);

    JsonNode err = call(postJson("/transfers", transfer(a, b, 1_001)), 422);

    assertThat(err.get("code").asText()).isEqualTo("INSUFFICIENT_FUNDS");
    assertThat(balance(a).get("balance").asLong()).isEqualTo(1_000);
    assertThat(balance(b).get("balance").asLong()).isZero();
  }

  @Test
  void overdraftAllowedUpToTheAccountLimit() throws Exception {
    long a = openAccount("SGD", 1_000, 500);
    long b = openAccount("SGD", 0, 0);

    call(postJson("/transfers", transfer(a, b, 1_500)), 201);
    call(postJson("/transfers", transfer(a, b, 1)), 422);

    assertThat(balance(a).get("balance").asLong()).isEqualTo(-500);
  }

  @Test
  void crossCurrencyTransferIsRejected() throws Exception {
    long sgd = openAccount("SGD", 1_000, 0);
    long usd = openAccount("USD", 0, 0);

    call(postJson("/transfers", transfer(sgd, usd, 100)), 400);

    assertThat(balance(sgd).get("balance").asLong()).isEqualTo(1_000);
  }

  @Test
  void invalidInputIsA400() throws Exception {
    long a = openAccount("SGD", 1_000, 0);
    call(postJson("/transfers", transfer(a, a, 100)), 400);
    call(postJson("/transfers", transfer(a, a + 1, -5)), 400);
    call(postJson("/accounts", Map.of("owner", "x", "currency", "sgd", "overdraftLimit", 0)), 400);
    call(get("/accounts/999999999/balance"), 404);
  }

  @Test
  void databaseRefusesAnOverdraftEvenIfTheCodeIsBypassed() {
    long id =
        jdbc.sql("INSERT INTO accounts(owner, currency) VALUES ('x', 'SGD') RETURNING id")
            .query(Long.class)
            .single();
    assertThatThrownBy(
            () -> jdbc.sql("UPDATE accounts SET balance = -1 WHERE id = ?").param(id).update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
