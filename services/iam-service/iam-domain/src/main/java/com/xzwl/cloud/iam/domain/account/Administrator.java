package com.xzwl.cloud.iam.domain.account;

public record Administrator(String id, String username, String passwordHash, String role,
                            boolean primary, boolean enabled, long version, String boundUserId) {
  public Administrator(String id, String username, String passwordHash, String role,
      boolean primary, boolean enabled, long version) {
    this(id, username, passwordHash, role, primary, enabled, version, null);
  }
  @Override public String toString() { return "Administrator[id=" + id + "]"; }
}
