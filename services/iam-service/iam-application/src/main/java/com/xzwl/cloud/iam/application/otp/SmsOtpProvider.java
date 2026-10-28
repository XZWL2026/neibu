package com.xzwl.cloud.iam.application.otp;

/** Local delivery or provider-managed challenge; neither credentials nor codes may be logged. */
@FunctionalInterface
public interface SmsOtpProvider {
  /** Production delivery keeps the randomly generated code. Local adapters may supply their configured code. */
  default String prepareCode(String generatedCode) { return generatedCode; }
  /** Reject challenges left over from a disabled or reconfigured test provider. Hash verification still applies. */
  default boolean acceptsCode(String code) { return true; }
  default String reference(String phone, String preparedCode, OtpCodes codes) { return codes.hash(phone, preparedCode); }
  default CheckResult verify(String phone, String code, String reference, OtpCodes codes) {
    return acceptsCode(code) && codes.matches(phone, code, reference) ? CheckResult.VALID : CheckResult.INVALID;
  }
  default boolean productionConfigured() { return false; }
  enum CheckResult { VALID, INVALID, EXPIRED }
  void send(String canonicalPhone, String code);
}
