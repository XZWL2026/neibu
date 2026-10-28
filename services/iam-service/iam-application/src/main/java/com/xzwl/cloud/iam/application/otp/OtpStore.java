package com.xzwl.cloud.iam.application.otp;

import com.xzwl.cloud.iam.domain.account.PhoneOtp;
import java.util.function.Function;

public interface OtpStore {
  <T> T locked(String phone, Function<PhoneOtp, T> work);
  void save(PhoneOtp state);
}
