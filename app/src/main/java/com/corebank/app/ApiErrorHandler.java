package com.corebank.app;

import com.corebank.app.ratelimit.RateLimitExceededException;
import com.corebank.common.DomainException;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves as application/problem+json (RFC 7807/9457): {@code type}, {@code title},
 * {@code status}, {@code detail}, {@code instance}, plus a machine-readable {@code code} for our
 * own errors. Framework errors (validation, missing header, unknown route, bad JSON) come from the
 * base class; 401/403 raised by the security filters come from {@code ProblemSecurityHandlers}.
 */
@RestControllerAdvice
public class ApiErrorHandler extends ResponseEntityExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

  /**
   * Framework errors (bad JSON, validation, missing header, 404/405/415) get the same {@code code}
   * property as ours, and body-validation failures list each rejected field.
   */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    // The base class passes body == null and resolves it from the exception; do that here so the
    // problem can be enriched before it is written.
    if (body == null && ex instanceof ErrorResponse er) {
      body = er.updateAndGetBody(getMessageSource(), LocaleContextHolder.getLocale());
    }
    if (body instanceof ProblemDetail pd) {
      if (pd.getProperties() == null || !pd.getProperties().containsKey("code")) {
        HttpStatus s = HttpStatus.resolve(status.value());
        pd.setProperty("code", status.value() == 400 ? "INVALID" : s != null ? s.name() : "ERROR");
      }
      if (ex instanceof MethodArgumentNotValidException invalid) {
        List<Map<String, String>> errors = invalid.getBindingResult().getFieldErrors().stream()
            .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
            .toList();
        pd.setProperty("errors", errors);
      }
    }
    return super.handleExceptionInternal(ex, body, headers, status, request);
  }

  @ExceptionHandler(DomainException.class)
  ProblemDetail domain(DomainException e) {
    return problem(HttpStatus.valueOf(e.status()), e.code(), e.getMessage());
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ProblemDetail constraint(ConstraintViolationException e) {
    return problem(HttpStatus.BAD_REQUEST, "INVALID", e.getMessage());
  }

  /** A DB constraint caught what the code did not: report a conflict, never a 500 with SQL. */
  @ExceptionHandler(DataIntegrityViolationException.class)
  ProblemDetail integrity(DataIntegrityViolationException e) {
    return problem(HttpStatus.CONFLICT, "CONSTRAINT_VIOLATION", "request violates a ledger constraint");
  }

  @ExceptionHandler(RateLimitExceededException.class)
  ResponseEntity<ProblemDetail> rateLimited(RateLimitExceededException e) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
        .body(problem(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", e.getMessage()));
  }

  /** Last resort: log the cause, return a generic 500 that leaks no internals. */
  @ExceptionHandler(Exception.class)
  ProblemDetail unexpected(Exception e) {
    log.error("unhandled error", e);
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "unexpected error");
  }

  private static ProblemDetail problem(HttpStatus status, String code, String detail) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
    pd.setType(URI.create("urn:problem:" + code.toLowerCase()));
    pd.setProperty("code", code);
    return pd;
  }
}
