package com.xzwl.cloud.access.domain.key;

import java.util.Objects;

public record PrimaryKey(String keyId, String accountId, KeyType type, String secret) {

  public PrimaryKey {
    requireId(keyId, "keyId");
    requireId(accountId, "accountId");
    type = Objects.requireNonNull(type, "type");
    secret = requireSecret(secret, type);
  }

  private static String requireSecret(String value, KeyType type) {
    if (value == null
        || (type == KeyType.MEDIA && !value.matches("^sk-_[0-9a-f]{64}$"))
        || (type == KeyType.API && !value.matches("^sk-[a-z0-9]{32}$"))) {
      throw new IllegalArgumentException("secret does not match key type format");
    }
    return value;
  }

  private static String requireId(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
