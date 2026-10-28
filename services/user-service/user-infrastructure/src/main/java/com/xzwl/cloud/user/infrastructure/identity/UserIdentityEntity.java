package com.xzwl.cloud.user.infrastructure.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xzwl.cloud.user.domain.identity.UserIdentity;

@TableName("user_identity")
public final class UserIdentityEntity {
  @TableId(type = IdType.INPUT)
  public String userId;
  public String phone;
  public String nickname;
  public String avatarFileId;
  public boolean enabled;
  public long authVersion;

  public UserIdentity toDomain() {
    return new UserIdentity(userId, phone, nickname, enabled, authVersion, avatarFileId);
  }
}
