package com.corebank.common;

/**
 * Business-rule violation. Carries the HTTP status it maps to so the web layer can translate any
 * module's errors uniformly without depending on each module's exception types.
 */
public class DomainException extends RuntimeException {
  private final int status;
  private final String code;

  public DomainException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static DomainException notFound(String what) {
    return new DomainException(404, "NOT_FOUND", what + " not found");
  }

  public static DomainException invalid(String message) {
    return new DomainException(400, "INVALID", message);
  }

  public static DomainException insufficientFunds(long accountId) {
    return new DomainException(422, "INSUFFICIENT_FUNDS", "insufficient funds on account " + accountId);
  }
}
