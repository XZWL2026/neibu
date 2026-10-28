package com.xzwl.cloud.billing.infrastructure.persistence;
import com.baomidou.mybatisplus.annotation.*;
import java.math.BigDecimal;
@TableName("media_charge_receipt")
public final class MediaChargeRow {
  @TableId(type=IdType.INPUT) public String operationId;
  public String ownerId,keyId,requestFacts,accountType,chargedAt;
  public BigDecimal amount4,rate4,points4;
}
