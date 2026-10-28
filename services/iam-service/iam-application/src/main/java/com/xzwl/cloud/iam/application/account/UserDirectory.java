package com.xzwl.cloud.iam.application.account;

public interface UserDirectory {
  UserIdentity resolve(String phone);
  UserIdentity find(String id);
  record UserIdentity(String id, String phone, String nickname, boolean enabled, long authVersion) {
    public AuthPrincipal principal() { return new AuthPrincipal(id, phone, "USER", false, authVersion); }
  }
}
