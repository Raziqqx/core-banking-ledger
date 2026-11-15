package com.corebank.payments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
@Validated
public class PaymentController {
  private final IdempotencyService idempotency;
  private final PaymentStore store;
  private final PaymentSaga saga;
  private final ObjectMapper json;

  public PaymentController(
      IdempotencyService idempotency, PaymentStore store, PaymentSaga saga, ObjectMapper json) {
    this.idempotency = idempotency;
    this.store = store;
    this.saga = saga;
    this.json = json;
  }

  /**
   * Creates and executes a payment. Responds 201 when it completed and 422 when the saga failed or
   * compensated; either way the body is the payment, and the exact response is stored against the
   * Idempotency-Key so a retry gets it back byte for byte.
   */
  @PostMapping
  public ResponseEntity<String> create(
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String key,
      @Valid @RequestBody PaymentRequest req)
      throws JsonProcessingException {
    return switch (idempotency.begin(key, req)) {
      case IdempotencyService.Conflict c -> respond(409, json.writeValueAsString(
          java.util.Map.of("code", "IDEMPOTENCY_CONFLICT", "detail", c.reason())));
      case IdempotencyService.Replay r -> respond(r.status(), r.body());
      case IdempotencyService.Claimed ignored -> {
        Payment p = saga.run(store.create(req));
        int status = p.status() == Payment.Status.COMPLETED ? 201 : 422;
        String body = json.writeValueAsString(p);
        idempotency.complete(key, p.id(), status, body);
        yield respond(status, body);
      }
    };
  }

  @GetMapping("/{id}")
  public Payment get(@PathVariable UUID id) {
    return store.get(id);
  }

  private static ResponseEntity<String> respond(int status, String body) {
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
  }
}
