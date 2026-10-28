package com.xzwl.cloud.billing.application.identity;

import java.util.Set;

public interface SessionVerifier {
  Identity verify(String cookie, String csrfToken, boolean unsafeRequest);

  record Identity(String userId, Set<String> roles) {
    public Identity { roles = Set.copyOf(roles); }
  }
  final class Unauthenticated extends RuntimeException {}
  final class CsrfDenied extends RuntimeException {}
  final class Unavailable extends RuntimeException {}
}
