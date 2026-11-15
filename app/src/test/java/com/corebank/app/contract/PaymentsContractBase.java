package com.corebank.app.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.corebank.accounts.AccountAccess;
import com.corebank.app.ApiErrorHandler;
import com.corebank.common.CurrentCaller;
import com.corebank.payments.IdempotencyService;
import com.corebank.payments.Payment;
import com.corebank.payments.PaymentController;
import com.corebank.payments.PaymentSaga;
import com.corebank.payments.PaymentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Base class for the tests Spring Cloud Contract generates from src/test/resources/contracts.
 * These verify the HTTP shape of the payments API (status, headers, JSON, problem details) that
 * consumers rely on, so the web layer is real (controller, validation, error handler, Jackson) and
 * everything behind it is stubbed. Auth and business behaviour are covered by the ITs.
 */
public abstract class PaymentsContractBase {
  static final UUID ID = UUID.fromString("7d1c2b3a-4e5f-4a6b-8c7d-9e0f1a2b3c4d");

  @BeforeEach
  void setup() {
    Payment pending = payment(Payment.Status.PENDING, null, null);
    Payment completed = payment(Payment.Status.COMPLETED, 11L, 22L);

    IdempotencyService idempotency = mock(IdempotencyService.class);
    when(idempotency.begin(anyString(), any())).thenReturn(new IdempotencyService.Claimed());
    PaymentStore store = mock(PaymentStore.class);
    when(store.create(any())).thenReturn(pending);
    when(store.get(ID)).thenReturn(completed);
    PaymentSaga saga = mock(PaymentSaga.class);
    when(saga.run(any())).thenReturn(completed);
    CurrentCaller caller = mock(CurrentCaller.class);
    when(caller.subject()).thenReturn("contract-client");

    ObjectMapper json = Jackson2ObjectMapperBuilder.json()
        .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
    PaymentController controller = new PaymentController(
        idempotency, store, saga, json, mock(AccountAccess.class), caller);
    RestAssuredMockMvc.mockMvc(MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new ApiErrorHandler())
        .setMessageConverters(new StringHttpMessageConverter(), new MappingJackson2HttpMessageConverter(json))
        .build());
  }

  private static Payment payment(Payment.Status status, Long holdId, Long entryId) {
    return new Payment(ID, 1, 2, 2_500, "SGD", "invoice-42", status, holdId, entryId, null,
        Instant.parse("2026-11-15T10:00:00Z"));
  }
}
