package com.xzwl.cloud.iam.application.account;

public interface PasswordHasher {
  String hash(String password);
  boolean matches(String password, String hash);
}
