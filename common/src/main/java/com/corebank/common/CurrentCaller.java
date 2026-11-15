package com.corebank.common;

/**
 * Who is calling the API. Implemented in the app module on top of Spring Security, so the domain
 * modules can apply ownership rules without depending on the security stack.
 */
public interface CurrentCaller {
  /** Stable identifier of the caller (JWT {@code sub}); matches {@code accounts.owner}. */
  String subject();

  /** Admins may act on any account; customers only on their own. */
  boolean isAdmin();
}
