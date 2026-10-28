package com.xzwl.cloud.iam;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xzwl.cloud.iam.application.account.AuthFailure;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class VolcengineSmsOtpProviderTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-15T03:04:05Z"), ZoneOffset.UTC);

  @Test void explicitProviderRejectsMissingConfigurationAndUnknownModes() {
    for (String mode : new String[]{"volcengine", "typo"}) {
      try (var app = new AnnotationConfigApplicationContext()) {
        app.getEnvironment().setActiveProfiles("prod");
        app.getEnvironment().getPropertySources().addFirst(new MapPropertySource("sms-test", Map.of("iam.sms.provider", mode)));
        app.register(OtpProviderConfiguration.class);
        assertThrows(BeansException.class, app::refresh);
      }
    }
  }

  @Test void localModeCannotSilentlyOverrideRealProvider() {
    try (var app = new AnnotationConfigApplicationContext()) {
      app.getEnvironment().setActiveProfiles("local");
      app.getEnvironment().getPropertySources().addFirst(new MapPropertySource("sms-test", Map.of(
          "iam.sms.provider", "volcengine", "iam.local-otp.enabled", "true")));
      app.register(OtpProviderConfiguration.class);
      assertThrows(BeansException.class, app::refresh);
    }
  }

  @Test void sendsTheExactOtpInSignedOfficialSendSmsRequest() throws Exception {
    var body = new AtomicReference<String>();
    var headers = new AtomicReference<com.sun.net.httpserver.Headers>();
    var request = new AtomicReference<String>();
    var count = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      count.incrementAndGet();
      body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      headers.set(exchange.getRequestHeaders());
      request.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
      byte[] response = "{\"ResponseMetadata\":{},\"Result\":{\"MessageID\":[\"accepted-id\"]}}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    try (var provider = provider(server, Duration.ofSeconds(2))) {
      assertEquals("135790", provider.prepareCode("135790"));
      provider.send("+8613800000001", "135790");
      assertEquals(1, count.get());
      assertEquals("POST /?Action=SendSms&Version=2020-01-01", request.get());
      var payload = JSON.readTree(body.get());
      assertEquals(5, payload.size());
      assertEquals("test-account", payload.path("SmsAccount").asText());
      assertEquals("测试签名", payload.path("Sign").asText());
      assertEquals("test-template", payload.path("TemplateID").asText());
      assertEquals("+8613800000001", payload.path("PhoneNumbers").asText());
      assertEquals(Map.of("verify_code", "135790"), JSON.readValue(payload.path("TemplateParam").asText(), Map.class));
      assertEquals("20260915T030405Z", headers.get().getFirst("X-Date"));
      assertEquals("application/json", headers.get().getFirst("Content-Type"));
      String hash = sha(body.get());
      assertEquals(hash, headers.get().getFirst("X-Content-Sha256"));
      String signedHeaders = "content-type;host;x-content-sha256;x-date";
      String canonical = String.join("\n", "POST", "/", "Action=SendSms&Version=2020-01-01",
          "content-type:application/json", "host:" + headers.get().getFirst("Host"),
          "x-content-sha256:" + hash, "x-date:20260915T030405Z", "", signedHeaders, hash);
      byte[] key = "test-secret".getBytes(StandardCharsets.UTF_8);
      for (String part : new String[]{"20260915", "cn-north-1", "volcSMS", "request"}) key = hmac(key, part);
      String scope = "20260915/cn-north-1/volcSMS/request";
      String signature = HexFormat.of().formatHex(hmac(key, "HMAC-SHA256\n20260915T030405Z\n" + scope + "\n" + sha(canonical)));
      assertEquals("HMAC-SHA256 Credential=test-access/" + scope + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature,
          headers.get().getFirst("Authorization"));
    } finally { server.stop(0); }
  }

  @Test void rejectionMalformedResponseAndHttpFailureRemainSafeAndNeverRetry() throws Exception {
    for (String response : new String[]{
        "{\"ResponseMetadata\":{\"Error\":{\"Code\":\"RE:0005\",\"Message\":\"test-secret 135790\"}},\"Result\":{\"MessageID\":[\"id\"]}}",
        "{\"Result\":{\"MessageID\":[]}}", "{\"Result\":{\"MessageID\":[\"\"]}}", "not-json test-secret 135790"}) {
      for (int status : new int[]{200, 503}) {
        var count = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
          count.incrementAndGet(); exchange.getRequestBody().readAllBytes();
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try (var provider = provider(server, Duration.ofSeconds(2))) {
          safeFailure(() -> provider.send("+8613800000001", "135790"));
          assertEquals(1, count.get());
        } finally { server.stop(0); }
      }
    }
  }

  @Test void stalledDeliveryTimesOutWithoutRetryAndInterruptedCallerKeepsItsFlag() throws Exception {
    var count = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> { count.incrementAndGet(); exchange.getRequestBody().readAllBytes(); });
    server.start();
    try (var provider = provider(server, Duration.ofMillis(250))) {
      assertTimeoutPreemptively(Duration.ofSeconds(2), () -> safeFailure(() -> provider.send("+8613800000001", "135790")));
      assertEquals(1, count.get());
      try {
        Thread.currentThread().interrupt();
        safeFailure(() -> provider.send("+8613800000001", "135790"));
        assertTrue(Thread.currentThread().isInterrupted());
      } finally { Thread.interrupted(); }
      assertEquals(1, count.get());
    } finally { server.stop(0); }
  }

  @Test void responseBodyCannotOutliveTheRequestDeadline() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(200, 200);
      exchange.getResponseBody().write('{');
      exchange.getResponseBody().flush();
    });
    server.start();
    try (var provider = provider(server, Duration.ofMillis(250))) {
      assertTimeoutPreemptively(Duration.ofSeconds(2), () -> safeFailure(() -> provider.send("+8613800000001", "135790")));
    } finally { server.stop(0); }
  }

  @Test void invalidPhoneOrCodeNeverReachesTransport() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try (var provider = provider(server, Duration.ofMillis(250))) {
      for (String phone : new String[]{null, "13800000001", "+8613800000001,+8613800000002"})
        assertThrows(IllegalArgumentException.class, () -> provider.send(phone, "135790"));
      for (String code : new String[]{null, "bad", "1234567"})
        assertThrows(IllegalArgumentException.class, () -> provider.send("+8613800000001", code));
    } finally { server.stop(0); }
  }

  @Test void malformedCredentialCannotEscapeInAHeaderException() {
    try (var provider = new VolcengineSmsOtpProvider("test-access\ninvalid", "test-secret", "account", "sign", "template", "code",
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
        URI.create("https://sms.volcengineapi.com"), CLOCK, Duration.ofSeconds(1))) {
      safeFailure(() -> provider.send("+8613800000001", "135790"));
    }
  }

  private static VolcengineSmsOtpProvider provider(HttpServer server, Duration timeout) {
    return new VolcengineSmsOtpProvider("test-access", "test-secret", "test-account", "测试签名", "test-template", "verify_code",
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
        URI.create("http://127.0.0.1:" + server.getAddress().getPort()), CLOCK, timeout);
  }

  private static void safeFailure(Runnable action) {
    var failure = assertThrows(AuthFailure.class, action::run);
    assertEquals(503, failure.status());
    assertEquals("SMS_PROVIDER_UNAVAILABLE", failure.getMessage());
    assertNull(failure.getCause());
  }

  private static String sha(String value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
  private static byte[] hmac(byte[] key, String value) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
  }
}
