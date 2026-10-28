package com.xzwl.cloud.iam.application.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Existing administrator-token login; a missing configured token disables this login path. */
public final class AdminLoginHandler {
  private final byte[] expected;
  public AdminLoginHandler(String configuredToken) {
    expected = configuredToken == null ? new byte[0] : configuredToken.getBytes(StandardCharsets.UTF_8);
  }
  public boolean authenticate(String provided) {
    return expected.length > 0 && provided != null
        && MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8));
  }
}
