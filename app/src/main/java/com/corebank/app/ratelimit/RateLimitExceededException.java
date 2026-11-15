package com.corebank.app.ratelimit;

/** Mapped to 429 with a Retry-After header by {@code ApiErrorHandler}. */
public class RateLimitExceededException extends RuntimeException {
  private final long retryAfterSeconds;

  public RateLimitExceededException(long retryAfterSeconds) {
    super("rate limit exceeded; retry after " + retryAfterSeconds + "s");
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
