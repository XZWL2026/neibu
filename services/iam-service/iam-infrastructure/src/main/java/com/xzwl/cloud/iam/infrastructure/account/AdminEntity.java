package com.xzwl.cloud.iam.infrastructure.account;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xzwl.cloud.iam.domain.account.Administrator;

@TableName("iam_admin")
public final class AdminEntity {
  @TableId(type = IdType.INPUT)
  public String adminId;
  public String username;
  public String passwordHash;
  public String role;
  public boolean isPrimary;
  public boolean enabled;
  public long authVersion;

  public String boundUserId;

  public Administrator toDomain() {
    return new Administrator(adminId, username, passwordHash, role, isPrimary, enabled, authVersion, boundUserId);
  }

  public static AdminEntity from(Administrator admin) {
    var row = new AdminEntity();
    row.adminId = admin.id();
    row.username = admin.username();
    row.passwordHash = admin.passwordHash();
    row.role = admin.role();
    row.isPrimary = admin.primary();
    row.enabled = admin.enabled();
    row.authVersion = admin.version();
    row.boundUserId = admin.boundUserId();
    return row;
  }
}
