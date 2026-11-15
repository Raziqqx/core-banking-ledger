package com.corebank.app;

import com.corebank.common.DomainException;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Maps errors to RFC 9457 problem details; bean-validation errors are handled by the base class. */
@RestControllerAdvice
public class ApiErrorHandler extends ResponseEntityExceptionHandler {

  @ExceptionHandler(DomainException.class)
  ProblemDetail domain(DomainException e) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.status()), e.getMessage());
    pd.setType(URI.create("urn:problem:" + e.code().toLowerCase()));
    pd.setProperty("code", e.code());
    return pd;
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ProblemDetail constraint(ConstraintViolationException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  /** A DB constraint caught what the code did not: report a conflict, never a 500 with SQL. */
  @ExceptionHandler(DataIntegrityViolationException.class)
  ProblemDetail integrity(DataIntegrityViolationException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "request violates a ledger constraint");
  }
}
