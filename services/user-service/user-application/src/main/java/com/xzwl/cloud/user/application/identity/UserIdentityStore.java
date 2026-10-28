package com.xzwl.cloud.user.application.identity;

import com.xzwl.cloud.user.domain.identity.UserIdentity;

public interface UserIdentityStore {
  UserIdentity resolve(String phone);
  default UserIdentity create(String requestId, String creatorId, String phone, String nickname) {
    return create(requestId, creatorId, phone, nickname, null, null);
  }
  UserIdentity create(String requestId, String creatorId, String phone, String nickname, String source, String remark);
  Page list(int offset, int limit, String search);
  record Page(java.util.List<UserIdentity> items, long total, int offset, int limit) {}
  final class Conflict extends RuntimeException {
    public Conflict(String code) { super(code); }
  }
  UserIdentity find(String id);
  UserIdentity updateProfile(String id, String nickname, String avatarFileId);
  UserIdentity rename(String id, String nickname);
  default java.time.Instant registeredAt(String id) { throw new IllegalStateException("registration lookup unavailable"); }
  void setEnabled(String id, boolean enabled);
}
