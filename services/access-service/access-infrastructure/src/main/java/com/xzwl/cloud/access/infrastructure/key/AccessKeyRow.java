package com.xzwl.cloud.access.infrastructure.key;
import com.baomidou.mybatisplus.annotation.*;
@TableName("access_key")
public final class AccessKeyRow {
  @TableId(type=IdType.INPUT) public String keyId;
  public String ownerId,accountType,keyType,secretHash,encryptedSecret,paidOrderId;
  public Boolean enabled;
}
