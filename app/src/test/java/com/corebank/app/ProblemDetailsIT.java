package com.corebank.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Every error class leaves as application/problem+json with status, detail and a code. */
class ProblemDetailsIT extends IntegrationTestBase {

  private JsonNode problem(MockHttpServletRequestBuilder req, int status, String code)
      throws Exception {
    req.header("Authorization", adminBearer());
    var res = mvc.perform(req).andReturn().getResponse();
    assertThat(res.getStatus()).isEqualTo(status);
    assertThat(res.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    JsonNode body = json.readTree(res.getContentAsString());
    assertThat(body.get("status").asInt()).isEqualTo(status);
    assertThat(body.get("code").asText()).isEqualTo(code);
    assertThat(body.hasNonNull("title")).isTrue();
    return body;
  }

  @Test
  void validationListsRejectedFields() throws Exception {
    JsonNode p = problem(postJson("/accounts", Map.of("owner", "", "currency", "sgd",
        "overdraftLimit", 0)), 400, "INVALID");
    assertThat(p.get("errors").findValuesAsText("field")).contains("owner", "currency");
  }

  @Test
  void frameworkErrorsShareTheShape() throws Exception {
    problem(post("/transfers").contentType(MediaType.APPLICATION_JSON).content("{not json"), 400,
        "INVALID");
    problem(postJson("/payments", Map.of("fromAccount", 1, "toAccount", 2, "amount", 1,
        "currency", "SGD")), 400, "INVALID"); // missing Idempotency-Key
    problem(get("/no-such-route"), 404, "NOT_FOUND");
    problem(post("/accounts").contentType(MediaType.TEXT_PLAIN).content("x"), 415,
        "UNSUPPORTED_MEDIA_TYPE");
  }

  @Test
  void domainErrorsCarryTheirCode() throws Exception {
    problem(get("/accounts/999999999"), 404, "NOT_FOUND");
    problem(get("/payments/00000000-0000-0000-0000-000000000000"), 404, "NOT_FOUND");
  }
}
