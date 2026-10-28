package com.xzwl.cloud.iam.infrastructure.account;

import com.xzwl.cloud.iam.application.account.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public final class BCryptPasswordHasher implements PasswordHasher {
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
  public String hash(String password) { return encoder.encode(password); }
  public boolean matches(String password, String hash) { return encoder.matches(password, hash); }
}
