package com.xzwl.cloud.iam.infrastructure.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.iam.application.account.AuthFailure;
import com.xzwl.cloud.iam.application.otp.OtpCodes;
import com.xzwl.cloud.iam.application.otp.SmsOtpProvider;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Fire-and-check protocol: the six-digit code exists only at Volcano and on the recipient's phone. */
public final class VolcanoSmsOtpProvider implements SmsOtpProvider {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String REGION = "cn-north-1", SERVICE = "volcSMS";
  private final String accessKey, secretKey, account, sign, template;
  private final URI endpoint;
  private final HttpClient http;
  private final Clock clock;
  private final Duration timeout;

  public VolcanoSmsOtpProvider(String accessKey, String secretKey, String account, String sign, String template) {
    this(accessKey, secretKey, account, sign, template, URI.create("https://sms.volcengineapi.com"),
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(),
        Clock.systemUTC(), Duration.ofSeconds(5));
  }

  // Package-only transport injection for local protocol tests; production has no endpoint override.
  VolcanoSmsOtpProvider(String accessKey, String secretKey, String account, String sign, String template,
      URI endpoint, HttpClient http, Clock clock, Duration timeout) {
    this.accessKey = required(accessKey, "IAM_SMS_ACCESS_KEY");
    this.secretKey = required(secretKey, "IAM_SMS_SECRET_KEY");
    this.account = required(account, "IAM_SMS_ACCOUNT");
    this.sign = required(sign, "IAM_SMS_SIGN");
    this.template = required(template, "IAM_SMS_TEMPLATE_ID");
    this.endpoint = endpoint; this.http = http; this.clock = clock; this.timeout = timeout;
  }

  @Override public boolean productionConfigured() { return true; }
  @Override public String prepareCode(String ignored) { return "volc:" + UUID.randomUUID(); }
  @Override public String reference(String phone, String preparedCode, OtpCodes codes) {
    if (!managed(preparedCode)) throw unavailable();
    return preparedCode;
  }
  @Override public void send(String phone, String reference) {
    var payload = payload(phone, reference);
    payload.put("Sign", sign); payload.put("TemplateID", template);
    payload.put("CodeType", 6); payload.put("ExpireTime", 300); payload.put("TryCount", 5);
    JsonNode ids = call("SendSmsVerifyCode", payload).path("MessageID");
    if (!ids.isArray() || ids.size() != 1 || !ids.get(0).isTextual() || ids.get(0).asText().isBlank()) throw unavailable();
  }
  @Override public CheckResult verify(String phone, String code, String reference, OtpCodes codes) {
    if (!managed(reference)) return CheckResult.EXPIRED;
    if (code == null || !code.matches("[0-9]{6}")) return CheckResult.INVALID;
    var payload = payload(phone, reference); payload.put("Code", code);
    JsonNode result = call("CheckSmsVerifyCode", payload);
    if (!result.isTextual()) throw unavailable();
    return switch (result.textValue()) {
      case "0" -> CheckResult.VALID;
      case "1" -> CheckResult.INVALID;
      case "2" -> CheckResult.EXPIRED;
      default -> throw unavailable();
    };
  }
  private Map<String,Object> payload(String phone, String reference) {
    if (!managed(reference)) throw unavailable();
    var result = new TreeMap<String,Object>();
    result.put("SmsAccount", account); result.put("PhoneNumber", phone);
    result.put("Scene", "login_" + reference.substring(5));
    return result;
  }
  private JsonNode call(String action, Map<String,Object> payload) {
    CompletableFuture<HttpResponse<String>> pending = null;
    try {
      // Neither operation is automatically retried: both mutate the provider's challenge state.
      pending = http.sendAsync(request(action, payload), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      // HttpRequest.timeout alone stops timing after headers on JDK 21; bound the complete body too.
      var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (response.statusCode() != 200) throw unavailable();
      JsonNode json = JSON.readTree(response.body());
      if (json == null || !json.path("ResponseMetadata").isObject()
          || json.path("ResponseMetadata").hasNonNull("Error") || !json.hasNonNull("Result")) throw unavailable();
      return json.get("Result");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt(); throw unavailable();
    } catch (Exception failure) {
      // JSON parsing / transport exceptions can include response text, codes or credentials.
      throw unavailable();
    } finally {
      if (pending != null && !pending.isDone()) pending.cancel(true);
    }
  }

  HttpRequest request(String action, Map<String,Object> payload) {
    try {
      String body = JSON.writeValueAsString(new TreeMap<>(payload));
      String datetime = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(clock.instant());
      String date = datetime.substring(0, 8), scope = date + "/" + REGION + "/" + SERVICE + "/request";
      String hash = hash(body), query = "Action=" + action + "&Version=2020-01-01";
      String signed = "content-type;host;x-content-sha256;x-date";
      String canonical = "POST\n/\n" + query + "\ncontent-type:application/json\nhost:" + endpoint.getRawAuthority()
          + "\nx-content-sha256:" + hash + "\nx-date:" + datetime + "\n\n" + signed + "\n" + hash;
      byte[] key = hmac(hmac(hmac(hmac(secretKey.getBytes(StandardCharsets.UTF_8), date), REGION), SERVICE), "request");
      String authorization = "HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signed
          + ", Signature=" + HexFormat.of().formatHex(hmac(key, "HMAC-SHA256\n" + datetime + "\n" + scope + "\n" + hash(canonical)));
      return HttpRequest.newBuilder(URI.create(endpoint + "/?" + query)).timeout(timeout)
          .header("Content-Type", "application/json").header("X-Date", datetime).header("X-Content-Sha256", hash)
          .header("Authorization", authorization).POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    } catch (Exception failure) { throw unavailable(); }
  }
  private static boolean managed(String reference) {
    return reference != null && reference.matches("volc:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  }
  private static String required(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be configured when IAM_SMS_ENABLED=true");
    return value;
  }
  private static String hash(String value) throws GeneralSecurityException {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
  private static byte[] hmac(byte[] key, String value) throws GeneralSecurityException {
    Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
  }
  private static AuthFailure unavailable() { return new AuthFailure(503, "SMS_PROVIDER_UNAVAILABLE"); }
}
