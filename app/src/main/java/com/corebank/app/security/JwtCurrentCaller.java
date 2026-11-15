package com.corebank.app.security;

import com.corebank.common.CurrentCaller;
import com.corebank.common.DomainException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** {@link CurrentCaller} backed by the authenticated JWT in the security context. */
@Component
public class JwtCurrentCaller implements CurrentCaller {
  /** Subjects are stored as part of scoped idempotency keys (VARCHAR(301) = 200 + ':' + 100). */
  static final int MAX_SUBJECT_LENGTH = 200;

  @Override
  public String subject() {
    String sub = auth().getName();
    if (sub == null || sub.isBlank() || sub.length() > MAX_SUBJECT_LENGTH) {
      throw new DomainException(401, "UNAUTHORIZED", "token subject is missing or too long");
    }
    return sub;
  }

  @Override
  public boolean isAdmin() {
    return auth().getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
  }

  private static Authentication auth() {
    Authentication a = SecurityContextHolder.getContext().getAuthentication();
    if (a == null || !a.isAuthenticated()) {
      throw new DomainException(401, "UNAUTHORIZED", "no authenticated caller");
    }
    return a;
  }
}
