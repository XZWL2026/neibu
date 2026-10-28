package com.xzwl.cloud.user.domain.identity;

public record UserIdentity(String id, String phone, String nickname, boolean enabled, long authVersion, String avatarFileId) {
  public UserIdentity(String id, String phone, String nickname, boolean enabled, long authVersion) {
    this(id, phone, nickname, enabled, authVersion, null);
  }
}
