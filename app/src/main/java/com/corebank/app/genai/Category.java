package com.corebank.app.genai;

import java.util.Locale;
import java.util.Optional;

/** The closed set of labels a categoriser may return. Anything else becomes {@link #OTHER}. */
public enum Category {
  GROCERIES, DINING, TRANSPORT, UTILITIES, RENT, SALARY, SHOPPING, ENTERTAINMENT, TRANSFER, OTHER;

  /** Strict parse of a model answer: exactly one known label, case-insensitive, nothing else. */
  public static Optional<Category> parse(String text) {
    if (text == null) {
      return Optional.empty();
    }
    String s = text.strip().toUpperCase(Locale.ROOT);
    for (Category c : values()) {
      if (c.name().equals(s)) {
        return Optional.of(c);
      }
    }
    return Optional.empty();
  }
}
