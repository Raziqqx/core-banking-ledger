package com.corebank.app.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 401/403 raised inside the security filter chain never reach {@code @RestControllerAdvice}, so
 * this writes the same problem+json shape. The bearer-token delegates set the RFC 6750
 * {@code WWW-Authenticate} header first.
 */
@Component
public class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {
  private final AuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
  private final AccessDeniedHandler bearerDenied = new BearerTokenAccessDeniedHandler();
  private final ObjectMapper json;

  public ProblemSecurityHandlers(ObjectMapper json) {
    this.json = json;
  }

  @Override
  public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException e)
      throws IOException, ServletException {
    bearerEntryPoint.commence(req, res, e);
    write(res, req, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "missing, invalid or expired bearer token");
  }

  @Override
  public void handle(HttpServletRequest req, HttpServletResponse res, AccessDeniedException e)
      throws IOException, ServletException {
    bearerDenied.handle(req, res, e);
    write(res, req, HttpStatus.FORBIDDEN, "FORBIDDEN", "the caller's role does not allow this operation");
  }

  private void write(
      HttpServletResponse res, HttpServletRequest req, HttpStatus status, String code, String detail)
      throws IOException {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
    pd.setType(URI.create("urn:problem:" + code.toLowerCase()));
    pd.setInstance(URI.create(req.getRequestURI()));
    pd.setProperty("code", code);
    res.setStatus(status.value());
    res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    json.writeValue(res.getOutputStream(), pd);
  }
}
