package com.corebank.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
 * across all integration test classes.
 */
@SpringBootTest
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

  protected JsonNode call(MockHttpServletRequestBuilder req, int expectedStatus) throws Exception {
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
    long id =
        call(postJson("/accounts", java.util.Map.of(
            "owner", "test", "currency", currency, "overdraftLimit", overdraftLimit)), 201)
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
