package com.corebank.payments;

import com.corebank.accounts.AccountAccess;
import com.corebank.common.CurrentCaller;
import com.corebank.common.DomainException;
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
  private final AccountAccess access;
  private final CurrentCaller caller;

  public PaymentController(
      IdempotencyService idempotency,
      PaymentStore store,
      PaymentSaga saga,
      ObjectMapper json,
      AccountAccess access,
      CurrentCaller caller) {
    this.idempotency = idempotency;
    this.store = store;
    this.saga = saga;
    this.json = json;
    this.access = access;
    this.caller = caller;
  }

  /**
   * Creates and executes a payment. Responds 201 when it completed and 422 when the saga failed or
   * compensated; either way the body is the payment, and the exact response is stored against the
   * Idempotency-Key so a retry gets it back byte for byte. Keys are scoped per caller, so two
   * clients choosing the same key never see each other's responses.
   */
  @PostMapping
  public ResponseEntity<String> create(
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String key,
      @Valid @RequestBody PaymentRequest req)
      throws JsonProcessingException {
    access.requireOwner(req.fromAccount());
    String scopedKey = caller.subject() + ":" + key;
    return switch (idempotency.begin(scopedKey, req)) {
      case IdempotencyService.Conflict c ->
          throw new DomainException(409, "IDEMPOTENCY_CONFLICT", c.reason());
      case IdempotencyService.Replay r -> respond(r.status(), r.body());
      case IdempotencyService.Claimed ignored -> {
        Payment p = saga.run(store.create(req));
        int status = p.status() == Payment.Status.COMPLETED ? 201 : 422;
        String body = json.writeValueAsString(p);
        idempotency.complete(scopedKey, p.id(), status, body);
        yield respond(status, body);
      }
    };
  }

  @GetMapping("/{id}")
  public Payment get(@PathVariable UUID id) {
    Payment p = store.get(id);
    access.requireOwnerOfAny(p.fromAccount(), p.toAccount());
    return p;
  }

  private static ResponseEntity<String> respond(int status, String body) {
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
  }
}
