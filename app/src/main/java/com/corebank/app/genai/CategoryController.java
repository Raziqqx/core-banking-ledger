package com.corebank.app.genai;

import com.corebank.accounts.AccountAccess;
import com.corebank.payments.Payment;
import com.corebank.payments.PaymentStore;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /payments/{id}/category}: same ownership rule as reading the payment. Exists only
 * behind the feature flag (see {@link CategoriserConfig}).
 */
@RestController
@ConditionalOnProperty(name = "genai.categoriser.enabled", havingValue = "true")
public class CategoryController {
  public record CategoryResponse(UUID paymentId, Category category, String provider) {}

  private final PaymentStore payments;
  private final AccountAccess access;
  private final TransactionCategoriser categoriser;

  CategoryController(PaymentStore payments, AccountAccess access, TransactionCategoriser categoriser) {
    this.payments = payments;
    this.access = access;
    this.categoriser = categoriser;
  }

  @GetMapping("/payments/{id}/category")
  public CategoryResponse category(@PathVariable UUID id) {
    Payment p = payments.get(id);
    access.requireOwnerOfAny(p.fromAccount(), p.toAccount());
    TransactionCategoriser.Result r = categoriser.categorise(
        new TransactionCategoriser.Transaction(p.reference(), p.amount(), p.currency()));
    return new CategoryResponse(p.id(), r.category(), r.provider());
  }
}
