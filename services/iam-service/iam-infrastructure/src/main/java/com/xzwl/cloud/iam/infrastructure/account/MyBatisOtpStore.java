package com.xzwl.cloud.iam.infrastructure.account;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.xzwl.cloud.iam.application.otp.OtpStore;
import com.xzwl.cloud.iam.domain.account.PhoneOtp;
import java.util.function.Function;
import org.springframework.transaction.support.TransactionTemplate;

public final class MyBatisOtpStore implements OtpStore {
  private final PhoneOtpMapper otps;
  private final TransactionTemplate transactions;
  public MyBatisOtpStore(PhoneOtpMapper otps, TransactionTemplate transactions) { this.otps = otps; this.transactions = transactions; }
  public <T> T locked(String phone, Function<PhoneOtp, T> work) {
    return transactions.execute(status -> {
      otps.insertIfAbsent(phone);
      return work.apply(otps.lockByPhone(phone).toDomain());
    });
  }
  public void save(PhoneOtp s) {
    otps.update(null, new UpdateWrapper<PhoneOtpEntity>().eq("phone", s.phone())
        .set("code_hash", s.codeHash()).set("expires_at", s.expiresAt()).set("last_sent_at", s.lastSentAt())
        .set("sent_day", s.sentDay()).set("sent_count", s.sentCount())
        .set("failures", s.failures()).set("consumed", s.consumed()));
  }
}
