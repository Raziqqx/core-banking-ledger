package com.corebank.app.genai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic keyword rules: the default provider, used in tests and local runs, so the feature
 * works without network access or an API key. The first matching rule wins (insertion order).
 */
public class MockCategoriser implements TransactionCategoriser {
  private static final Map<Category, List<String>> RULES = new LinkedHashMap<>();

  static {
    RULES.put(Category.SALARY, List.of("salary", "payroll", "wages"));
    RULES.put(Category.RENT, List.of("rent", "landlord", "lease"));
    RULES.put(Category.UTILITIES, List.of("electric", "water", "utilities", "sp group", "internet", "mobile bill"));
    RULES.put(Category.GROCERIES, List.of("grocer", "supermarket", "ntuc", "fairprice", "cold storage"));
    RULES.put(Category.DINING, List.of("restaurant", "cafe", "coffee", "lunch", "dinner", "hawker"));
    RULES.put(Category.TRANSPORT, List.of("grab", "taxi", "mrt", "bus", "fuel", "parking"));
    RULES.put(Category.ENTERTAINMENT, List.of("cinema", "netflix", "spotify", "concert", "game"));
    RULES.put(Category.SHOPPING, List.of("shop", "store", "amazon", "lazada", "shopee"));
    RULES.put(Category.TRANSFER, List.of("transfer", "repay", "split", "invoice"));
  }

  @Override
  public Result categorise(Transaction tx) {
    String ref = tx.reference() == null ? "" : tx.reference().toLowerCase(Locale.ROOT);
    for (Map.Entry<Category, List<String>> rule : RULES.entrySet()) {
      if (rule.getValue().stream().anyMatch(ref::contains)) {
        return new Result(rule.getKey(), "mock");
      }
    }
    return new Result(Category.OTHER, "mock");
  }
}
