package com.corebank.app;

import com.corebank.app.security.DevTokenIssuer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Starts Postgres and Redpanda once per JVM (singleton containers) and shares one Spring context
 * across all integration test classes. Runs under the {@code dev} profile so tokens come from the
 * in-memory {@link DevTokenIssuer}; {@link #call} sends an admin token unless the request already
 * carries an Authorization header. The payment rate limit is lifted here (see RateLimitIT).
 */
@SpringBootTest(properties = "ratelimit.payments.capacity=1000000")
@ActiveProfiles("dev")
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
  static final RedpandaContainer REDPANDA =
      new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.2.18");

  static {
    POSTGRES.start();
    REDPANDA.start();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
    r.add("spring.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
  }

  @Autowired protected MockMvc mvc;
  @Autowired protected ObjectMapper json;
  @Autowired protected DevTokenIssuer tokens;

  protected String bearer(String subject, String... roles) {
    return "Bearer " + tokens.issue(subject, List.of(roles), Duration.ofMinutes(5));
  }

  protected String adminBearer() {
    return bearer("test-admin", "ADMIN");
  }

  protected JsonNode call(MockHttpServletRequestBuilder req, int expectedStatus) throws Exception {
    String admin = adminBearer();
    req.with(r -> {
      if (r.getHeader(HttpHeaders.AUTHORIZATION) == null) {
        r.addHeader(HttpHeaders.AUTHORIZATION, admin);
      }
      return r;
    });
    MvcResult r = mvc.perform(req).andReturn();
    String body = r.getResponse().getContentAsString();
    if (r.getResponse().getStatus() != expectedStatus) {
      throw new AssertionError(
          "expected " + expectedStatus + " but got " + r.getResponse().getStatus() + ": " + body);
    }
    return body.isEmpty() ? null : json.readTree(body);
  }

  protected MockHttpServletRequestBuilder postJson(String url, Object body) throws Exception {
    return post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
  }

  /** Opens an account and optionally funds it from the system account; returns its id. */
  protected long openAccount(String currency, long deposit, long overdraftLimit) throws Exception {
    return openAccount("test", currency, deposit, overdraftLimit);
  }

  /** Opens (as admin) an account owned by {@code owner}, i.e. a JWT subject. */
  protected long openAccount(String owner, String currency, long deposit, long overdraftLimit)
      throws Exception {
    long id =
        call(postJson("/accounts", java.util.Map.of(
            "owner", owner, "currency", currency, "overdraftLimit", overdraftLimit)), 201)
            .get("id")
            .asLong();
    if (deposit > 0) {
      call(postJson("/accounts/" + id + "/deposits", java.util.Map.of("amount", deposit)), 201);
    }
    return id;
  }

  protected JsonNode balance(long id) throws Exception {
    return call(get("/accounts/" + id + "/balance"), 200);
  }
}
