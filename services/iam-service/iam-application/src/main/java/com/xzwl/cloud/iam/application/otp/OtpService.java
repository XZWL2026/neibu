package com.xzwl.cloud.iam.application.otp;

import com.xzwl.cloud.iam.application.account.*;
import com.xzwl.cloud.iam.domain.account.PhoneOtp;
import java.time.*;

public final class OtpService {
  private final OtpStore store;
  private final SmsOtpProvider provider;
  private final OtpCodes codes;
  private final UserDirectory users;
  private final Clock clock;
  public OtpService(OtpStore store, SmsOtpProvider provider, OtpCodes codes, UserDirectory users, Clock clock) {
    this.store = store; this.provider = provider; this.codes = codes; this.users = users; this.clock = clock;
  }
  public void send(String suppliedPhone) {
    String phone = phone(suppliedPhone);
    Reservation reservation = store.locked(phone, state -> {
      long now = clock.millis();
      LocalDate today = LocalDate.ofInstant(Instant.ofEpochMilli(now), ZoneId.of("Asia/Shanghai"));
      if (state.lastSentAt() != null && now < state.lastSentAt() + 60_000) throw new AuthFailure(429, "OTP_RESEND_TOO_SOON");
      int count = today.equals(state.sentDay()) ? state.sentCount() : 0;
      if (count >= 5) throw new AuthFailure(429, "OTP_DAILY_LIMIT");
      String code;
      do { code = codes.generate(); } while (codes.hash(phone, code).equals(state.codeHash()));
      code = provider.prepareCode(code);
      var next = new PhoneOtp(phone, provider.reference(phone, code, codes), now + 300_000, now, today, count + 1, 0, true);
      store.save(next);
      return new Reservation(code, next.codeHash());
    });
    // Commit quota before the external effect, including when a process dies during delivery.
    boolean delivered;
    try { provider.send(phone, reservation.code()); delivered = true; }
    catch (RuntimeException failure) { delivered = false; }
    final boolean accepted = delivered;
    boolean activated = store.locked(phone, state -> {
      if (reservation.hash().equals(state.codeHash())) {
        if (accepted && clock.millis() < state.expiresAt()) {
          store.save(new PhoneOtp(phone, state.codeHash(), state.expiresAt(), state.lastSentAt(), state.sentDay(), state.sentCount(), 0, false));
          return true;
        } else store.save(state.consumedCopy());
      }
      return false;
    });
    if (!delivered) throw new AuthFailure(503, "SMS_PROVIDER_UNAVAILABLE");
    if (!activated) throw new AuthFailure(503, "OTP_DELIVERY_EXPIRED_OR_REPLACED");
  }

  public AuthPrincipal login(String suppliedPhone, String code) {
    String phone = phone(suppliedPhone);
    Verification result = store.locked(phone, state -> {
      if (state.consumed() || state.codeHash() == null || clock.millis() >= state.expiresAt()) return new Verification(null, new AuthFailure(401, "OTP_INVALID_OR_EXPIRED"));
      if (state.failures() >= 5) return new Verification(null, new AuthFailure(423, "OTP_LOCKED"));
      var checked = provider.verify(phone, code, state.codeHash(), codes);
      if (checked == SmsOtpProvider.CheckResult.EXPIRED || clock.millis() >= state.expiresAt()) {
        store.save(state.consumedCopy());
        return new Verification(null, new AuthFailure(401, "OTP_INVALID_OR_EXPIRED"));
      }
      if (checked != SmsOtpProvider.CheckResult.VALID) {
        var next = state.wrongAttempt(); store.save(next);
        return new Verification(null, new AuthFailure(next.failures() >= 5 ? 423 : 401, next.failures() >= 5 ? "OTP_LOCKED" : "OTP_INVALID_OR_EXPIRED"));
      }
      var user = users.resolve(phone);
      store.save(state.consumedCopy());
      if (!user.enabled()) return new Verification(null, new AuthFailure(403, "USER_DISABLED"));
      return new Verification(user.principal(), null);
    });
    // Failed attempts must commit their counters before an HTTP error is thrown.
    if (result.error() != null) throw result.error();
    return result.principal();
  }
  public static String phone(String value) {
    if (value == null) throw new AuthFailure(400, "INVALID_PHONE");
    String canonical = value.trim();
    if (canonical.matches("1[3-9][0-9]{9}")) canonical = "+86" + canonical;
    if (!canonical.matches("\\+[1-9][0-9]{7,14}")) throw new AuthFailure(400, "INVALID_PHONE");
    return canonical;
  }
  private record Verification(AuthPrincipal principal, AuthFailure error) {}
  private record Reservation(String code, String hash) {
    @Override public String toString() { return "Reservation[redacted]"; }
  }
}
