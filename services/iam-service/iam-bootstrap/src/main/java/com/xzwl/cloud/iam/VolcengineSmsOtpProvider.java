package com.xzwl.cloud.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.iam.application.account.AuthFailure;
import com.xzwl.cloud.iam.application.otp.SmsOtpProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** SendSms delivers the code owned by OtpService; it never generates a second provider-managed code. */
final class VolcengineSmsOtpProvider implements SmsOtpProvider, AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String QUERY = "Action=SendSms&Version=2020-01-01";
  private static final String SIGNED_HEADERS = "content-type;host;x-content-sha256;x-date";
  private final String accessKey, secretKey, smsAccount, sign, templateId, codeParam;
  private final HttpClient http;
  private final URI endpoint;
  private final Clock clock;
  private final Duration timeout;

  VolcengineSmsOtpProvider(String accessKey, String secretKey, String smsAccount, String sign,
      String templateId, String codeParam, HttpClient http, URI endpoint, Clock clock, Duration timeout) {
    for (String value : new String[]{accessKey, secretKey, smsAccount, sign, templateId, codeParam}) {
      if (value == null || value.isBlank()) throw new IllegalStateException("Volcengine SMS configuration is incomplete");
    }
    this.accessKey = accessKey; this.secretKey = secretKey; this.smsAccount = smsAccount;
    this.sign = sign; this.templateId = templateId; this.codeParam = codeParam;
    this.http = http; this.endpoint = endpoint; this.clock = clock; this.timeout = timeout;
  }

  @Override public void send(String phone, String code) {
    if (phone == null || !phone.matches("\\+[1-9][0-9]{7,14}") || code == null || !code.matches("[0-9]{6}"))
      throw new IllegalArgumentException("SMS requires a canonical phone and six-digit code");
    try {
      var payload = new LinkedHashMap<String, String>();
      payload.put("SmsAccount", smsAccount); payload.put("Sign", sign); payload.put("TemplateID", templateId);
      payload.put("TemplateParam", JSON.writeValueAsString(Map.of(codeParam, code))); payload.put("PhoneNumbers", phone);
      String body = JSON.writeValueAsString(payload);
      String datetime = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(clock.instant());
      String date = datetime.substring(0, 8), scope = date + "/cn-north-1/volcSMS/request", hash = hash(body);
      String canonical = "POST\n/\n" + QUERY + "\ncontent-type:application/json\nhost:" + endpoint.getRawAuthority()
          + "\nx-content-sha256:" + hash + "\nx-date:" + datetime + "\n\n" + SIGNED_HEADERS + "\n" + hash;
      byte[] key = hmac(hmac(hmac(hmac(secretKey.getBytes(StandardCharsets.UTF_8), date), "cn-north-1"), "volcSMS"), "request");
      String authorization = "HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + SIGNED_HEADERS
          + ", Signature=" + HexFormat.of().formatHex(hmac(key, "HMAC-SHA256\n" + datetime + "\n" + scope + "\n" + hash(canonical)));
      // HttpClient supplies the Host header used in the signature. No redirects or application retries.
      var request = HttpRequest.newBuilder(endpoint.resolve("/?" + QUERY)).timeout(timeout)
          .header("Content-Type", "application/json").header("X-Date", datetime).header("X-Content-Sha256", hash)
          .header("Authorization", authorization).POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
      var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      HttpResponse<String> response;
      // HttpRequest.timeout stops at response headers; bound the complete body and cancel stalled work too.
      try { response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
      finally { if (!pending.isDone()) pending.cancel(true); }
      if (response.statusCode() != 200) throw unavailable();
      var result = JSON.readTree(response.body());
      if (result == null || !result.path("ResponseMetadata").isObject() || result.path("ResponseMetadata").hasNonNull("Error"))
        throw unavailable();
      var ids = result.path("Result").path("MessageID");
      if (!ids.isArray() || ids.size() != 1 || !ids.get(0).isTextual() || ids.get(0).asText().isBlank()) throw unavailable();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (IOException | ExecutionException | TimeoutException | IllegalArgumentException failure) {
      // Provider bodies and exception causes can contain codes, phone numbers or credentials.
      throw unavailable();
    }
  }

  private static AuthFailure unavailable() { return new AuthFailure(503, "SMS_PROVIDER_UNAVAILABLE"); }
  private static String hash(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (GeneralSecurityException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
  }
  private static byte[] hmac(byte[] key, String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException impossible) { throw new IllegalStateException("HMAC unavailable"); }
  }
  @Override public void close() { http.close(); }
}
