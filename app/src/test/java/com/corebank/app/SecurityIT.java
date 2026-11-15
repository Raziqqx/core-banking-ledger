package com.corebank.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 401/403 and ownership rules, with real RS256 tokens from the dev issuer. */
class SecurityIT extends IntegrationTestBase {

  private MockHttpServletRequestBuilder pay(String bearer, long from, long to, long amount) throws Exception {
    return postJson("/payments", Map.of("fromAccount", from, "toAccount", to, "amount", amount,
            "currency", "SGD"))
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .header("Authorization", bearer);
  }

  @Test
  void missingTokenIs401ProblemWithBearerChallenge() throws Exception {
    MvcResult r = mvc.perform(get("/accounts/1")).andReturn();
    assertThat(r.getResponse().getStatus()).isEqualTo(401);
    assertThat(r.getResponse().getHeader("WWW-Authenticate")).startsWith("Bearer");
    assertThat(r.getResponse().getContentType()).isEqualTo("application/problem+json");
    assertThat(json.readTree(r.getResponse().getContentAsString()).get("code").asText())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void tamperedExpiredOrForeignTokensAre401() throws Exception {
    String good = tokens.issue("alice", List.of("CUSTOMER"), Duration.ofMinutes(5));
    String tampered = good.substring(0, good.length() - 4) + "AAAA";
    // Expired beyond the default 60 s clock-skew allowance.
    String expired = tokens.issue("alice", List.of("CUSTOMER"),
        java.time.Instant.now().minusSeconds(600), Duration.ofMinutes(5));
    // Well-formed and signed, but by a key this service does not trust.
    String foreign = new com.corebank.app.security.DevTokenIssuer()
        .issue("alice", List.of("ADMIN"), Duration.ofMinutes(5));
    for (String t : List.of(tampered, expired, foreign, "not-a-jwt")) {
      call(get("/accounts/1").header("Authorization", "Bearer " + t), 401);
    }
  }

  @Test
  void customerCannotOpenAccountsOrCallAdminEndpoints() throws Exception {
    String alice = bearer("alice", "CUSTOMER");
    JsonNode err = call(postJson("/accounts", Map.of("owner", "alice", "currency", "SGD",
        "overdraftLimit", 0)).header("Authorization", alice), 403);
    assertThat(err.get("code").asText()).isEqualTo("FORBIDDEN");
    call(get("/ledger/audit").header("Authorization", alice), 403);
    call(postJson("/transfers", Map.of("fromAccount", 1, "toAccount", 2, "amount", 1))
        .header("Authorization", alice), 403);
  }

  @Test
  void tokenWithoutAKnownRoleIs403() throws Exception {
    call(get("/accounts/1").header("Authorization", bearer("mallory", "AUDITOR")), 403);
  }

  @Test
  void customerSeesOnlyOwnAccounts() throws Exception {
    long mine = openAccount("alice", "SGD", 1_000, 0);
    long theirs = openAccount("bob", "SGD", 1_000, 0);
    String alice = bearer("alice", "CUSTOMER");

    assertThat(call(get("/accounts/" + mine).header("Authorization", alice), 200)
        .get("owner").asText()).isEqualTo("alice");
    call(get("/accounts/" + mine + "/balance").header("Authorization", alice), 200);
    call(get("/accounts/" + mine + "/history").header("Authorization", alice), 200);

    for (String path : List.of("", "/balance", "/history")) {
      JsonNode err = call(get("/accounts/" + theirs + path).header("Authorization", alice), 403);
      assertThat(err.get("code").asText()).isEqualTo("FORBIDDEN");
    }
  }

  @Test
  void customerPaysOnlyFromOwnAccountAndReadsOnlyOwnPayments() throws Exception {
    long alice = openAccount("alice", "SGD", 10_000, 0);
    long bob = openAccount("bob", "SGD", 0, 0);
    long carol = openAccount("carol", "SGD", 10_000, 0);
    String aliceToken = bearer("alice", "CUSTOMER");

    JsonNode p = call(pay(aliceToken, alice, bob, 2_500), 201);
    call(pay(aliceToken, carol, alice, 100), 403); // spending someone else's money
    assertThat(balance(carol).get("balance").asLong()).isEqualTo(10_000);

    String id = p.get("id").asText();
    call(get("/payments/" + id).header("Authorization", aliceToken), 200);
    call(get("/payments/" + id).header("Authorization", bearer("bob", "CUSTOMER")), 200); // payee
    call(get("/payments/" + id).header("Authorization", bearer("carol", "CUSTOMER")), 403);
  }

  @Test
  void idempotencyKeysAreScopedPerCaller() throws Exception {
    long alice = openAccount("alice", "SGD", 10_000, 0);
    long dave = openAccount("dave", "SGD", 10_000, 0);
    long sink = openAccount("sink", "SGD", 0, 0);
    String key = UUID.randomUUID().toString();
    // Same key, different callers and bodies: no 409, two independent payments.
    JsonNode a = call(pay(bearer("alice", "CUSTOMER"), alice, sink, 100)
        .header("Idempotency-Key", key), 201);
    JsonNode d = call(postJson("/payments", Map.of("fromAccount", dave, "toAccount", sink,
            "amount", 200, "currency", "SGD"))
        .header("Idempotency-Key", key).header("Authorization", bearer("dave", "CUSTOMER")), 201);
    assertThat(a.get("id")).isNotEqualTo(d.get("id"));
    assertThat(balance(sink).get("balance").asLong()).isEqualTo(300);
  }

  @Test
  void openApiAndSwaggerUiArePublic() throws Exception {
    JsonNode doc = json.readTree(mvc.perform(get("/v3/api-docs")).andReturn().getResponse()
        .getContentAsString());
    assertThat(doc.get("paths").has("/payments")).isTrue();
    assertThat(doc.at("/components/securitySchemes/bearer/scheme").asText()).isEqualTo("bearer");
    assertThat(mvc.perform(get("/swagger-ui/index.html")).andReturn().getResponse().getStatus())
        .isEqualTo(200);
  }

  @Test
  void devTokenEndpointIssuesUsableTokens() throws Exception {
    MvcResult r = mvc.perform(postJson("/dev/token",
        Map.of("subject", "erin", "roles", List.of("CUSTOMER")))).andReturn();
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    String token = json.readTree(r.getResponse().getContentAsString()).get("access_token").asText();
    long mine = openAccount("erin", "SGD", 0, 0);
    call(get("/accounts/" + mine).header("Authorization", "Bearer " + token), 200);
  }
}
