package com.corebank.app.genai;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebank.app.genai.TransactionCategoriser.Transaction;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class MockCategoriserTest {
  private final MockCategoriser mock = new MockCategoriser();

  @ParameterizedTest
  @CsvSource({
      "NTUC FairPrice Tampines, GROCERIES",
      "June salary, SALARY",
      "Rent for May, RENT",
      "Grab ride to office, TRANSPORT",
      "Hawker lunch, DINING",
      "SP Group electric bill, UTILITIES",
      "Netflix subscription, ENTERTAINMENT",
      "Shopee order 123, SHOPPING",
      "invoice-42, TRANSFER",
      "something unknown, OTHER"})
  void labelsByKeyword(String reference, Category expected) {
    assertThat(mock.categorise(new Transaction(reference, 1_000, "SGD")).category())
        .isEqualTo(expected);
  }

  @Test
  void nullReferenceIsOtherAndResultsAreDeterministic() {
    assertThat(mock.categorise(new Transaction(null, 1, "SGD")).category()).isEqualTo(Category.OTHER);
    Transaction tx = new Transaction("coffee", 450, "SGD");
    assertThat(mock.categorise(tx)).isEqualTo(mock.categorise(tx));
    assertThat(mock.categorise(tx).provider()).isEqualTo("mock");
  }

  @Test
  void parseAcceptsOnlyAnExactLabel() {
    assertThat(Category.parse(" groceries\n")).contains(Category.GROCERIES);
    assertThat(Category.parse("GROCERIES because it is food")).isEmpty();
    assertThat(Category.parse("")).isEmpty();
    assertThat(Category.parse(null)).isEmpty();
  }
}
