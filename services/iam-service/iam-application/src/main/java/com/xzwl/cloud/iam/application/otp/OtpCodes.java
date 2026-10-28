package com.xzwl.cloud.iam.application.otp;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class OtpCodes {
  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();
  public OtpCodes(String secret) {
    if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) throw new IllegalStateException("IAM_OTP_HMAC_KEY must contain at least 32 bytes");
    key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }
  public String generate() { return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000)); }
  public String hash(String phone, String code) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256"); mac.init(key);
      return HexFormat.of().formatHex(mac.doFinal((phone + "\u0000" + code).getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) { throw new IllegalStateException("OTP cryptography unavailable"); }
  }
  public boolean matches(String phone, String code, String expected) {
    return code != null && code.matches("[0-9]{6}") && expected != null
        && MessageDigest.isEqual(hash(phone, code).getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII));
  }
}
