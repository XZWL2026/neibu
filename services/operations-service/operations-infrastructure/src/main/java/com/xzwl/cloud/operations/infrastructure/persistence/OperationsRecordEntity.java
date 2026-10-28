package com.xzwl.cloud.operations.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;

/** The existing composite key is addressed by wrappers or explicit locking SQL, never by ID APIs. */
@TableName("operations_record")
public class OperationsRecordEntity {
  public String recordType;
  public String recordId;
  public String userId;
  public String businessStatus;
  public BigDecimal amount4;
  public String occurredAt;
  public String summary;
  public String detailsJson;
  public Long sourceVersion;
  public String projectedAt;
}
