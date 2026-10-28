package com.xzwl.cloud.iam.infrastructure.account;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xzwl.cloud.iam.domain.account.PhoneOtp;
import java.time.LocalDate;

@TableName("iam_phone_otp")
public final class PhoneOtpEntity {
  @TableId(type = IdType.INPUT)
  public String phone;
  public String codeHash;
  public long expiresAt;
  public Long lastSentAt;
  public LocalDate sentDay;
  public int sentCount;
  public int failures;
  public boolean consumed;

  public PhoneOtp toDomain() {
    return new PhoneOtp(phone, codeHash, expiresAt, lastSentAt, sentDay, sentCount, failures, consumed);
  }
}
