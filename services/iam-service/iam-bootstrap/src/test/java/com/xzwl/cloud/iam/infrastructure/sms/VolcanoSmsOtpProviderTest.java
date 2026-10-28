package com.xzwl.cloud.iam.infrastructure.sms;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xzwl.cloud.iam.application.account.AuthFailure;
import com.xzwl.cloud.iam.application.otp.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class VolcanoSmsOtpProviderTest {
  static final String PHONE = "+8613800000001";
  static final String REF = "volc:00000000-0000-0000-0000-000000000001";
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneOffset.UTC);
  static final OtpCodes CODES = new OtpCodes("test-only-hmac-key-at-least-thirty-two-bytes");
  static final ObjectMapper JSON = new ObjectMapper();

  @Test void signsAgainstFixedV4Vector() {
    // Independently calculated from official SignerV4 canonicalization; no production helper in expected value.
    var provider = provider(URI.create("https://sms.volcengineapi.com"), Duration.ofSeconds(5));
    var request = provider.request("CheckSmsVerifyCode", Map.of("Code", "246810", "PhoneNumber", PHONE,
        "Scene", "login_" + REF.substring(5), "SmsAccount", "test-account"));
    assertEquals("20260921T000000Z", request.headers().firstValue("X-Date").orElseThrow());
    assertEquals("7f0b33ea42d91e6bbb7c86acc786a7bdac4db76b2b868e44d22870a1ea89b1ea", request.headers().firstValue("X-Content-Sha256").orElseThrow());
    assertEquals("HMAC-SHA256 Credential=test-ak/20260921/cn-north-1/volcSMS/request, SignedHeaders=content-type;host;x-content-sha256;x-date, Signature=c8fb7e81d90115d39ff03cace30f946c914fd5dc2c27ca1987fcb471dd7068d9", request.headers().firstValue("Authorization").orElseThrow());
    assertEquals("https://sms.volcengineapi.com/?Action=CheckSmsVerifyCode&Version=2020-01-01", request.uri().toString());
  }

  @Test void sendsManagedParametersAndChecksTheSameScene() throws Exception {
    try (var f = new Fixture()) {
      var provider = provider(f.endpoint, Duration.ofSeconds(2));
      String ref = provider.prepareCode("123456");
      assertEquals(ref, provider.reference(PHONE, ref, CODES));
      assertFalse(ref.contains("123456"));
      provider.send(PHONE, ref);
      var send = f.requests.take();
      var body = JSON.readTree(send.body());
      assertEquals("POST", send.method());
      assertEquals("Action=SendSmsVerifyCode&Version=2020-01-01", send.query());
      assertEquals("test-account", body.path("SmsAccount").asText());
      assertEquals("测试签名", body.path("Sign").asText());
      assertEquals("test-template", body.path("TemplateID").asText());
      assertEquals(PHONE, body.path("PhoneNumber").asText());
      assertEquals(6, body.path("CodeType").asInt());
      assertEquals(300, body.path("ExpireTime").asInt());
      assertEquals(5, body.path("TryCount").asInt());
      assertFalse(body.has("Code")); assertFalse(body.has("TemplateParam"));
      f.response = "{\"ResponseMetadata\":{},\"Result\":\"0\"}";
      assertEquals(SmsOtpProvider.CheckResult.VALID, provider.verify(PHONE, "246810", ref, CODES));
      var check = f.requests.take(); var checked = JSON.readTree(check.body());
      assertEquals("Action=CheckSmsVerifyCode&Version=2020-01-01", check.query());
      assertEquals(body.path("Scene"), checked.path("Scene"));
      assertEquals("246810", checked.path("Code").asText());
      assertEquals(PHONE, checked.path("PhoneNumber").asText());
      assertEquals("test-account", checked.path("SmsAccount").asText());
    }
  }

  @Test void mapsWrongAndExpiredButRejectsAllAmbiguousAndBusinessFailureResponses() throws Exception {
    try (var f = new Fixture()) {
      var provider = provider(f.endpoint, Duration.ofSeconds(2));
      for (String value : List.of("1", "2")) {
        f.response = "{\"ResponseMetadata\":{},\"Result\":\"" + value + "\"}";
        assertEquals(value.equals("1") ? SmsOtpProvider.CheckResult.INVALID : SmsOtpProvider.CheckResult.EXPIRED,
            provider.verify(PHONE, "246810", REF, CODES));
      }
      for (String response : List.of("{}", "null", "broken-json", "{\"Result\":\"0\"}",
          "{\"ResponseMetadata\":{},\"Result\":0}", "{\"ResponseMetadata\":{},\"Result\":\"unknown\"}",
          "{\"ResponseMetadata\":{\"Error\":{\"Code\":\"RE:0000\",\"Message\":\"test-secret-never-print\"}},\"Result\":\"0\"}")) {
        f.response = response; int before = f.calls.get();
        var failure = assertThrows(AuthFailure.class, () -> provider.verify(PHONE, "246810", REF, CODES));
        assertEquals(503, failure.status()); assertEquals("SMS_PROVIDER_UNAVAILABLE", failure.getMessage());
        assertNull(failure.getCause()); assertEquals(before + 1, f.calls.get());
      }
      for (String response : List.of("{}", "{\"ResponseMetadata\":{},\"Result\":{\"MessageID\":[]}}",
          "{\"ResponseMetadata\":{},\"Result\":{\"MessageID\":[null]}}",
          "{\"ResponseMetadata\":{\"Error\":{\"Code\":\"RE:0010\"}},\"Result\":{\"MessageID\":[\"id\"]}}")) {
        f.response = response; assertThrows(AuthFailure.class, () -> provider.send(PHONE, REF));
      }
      for (int status : new int[]{302, 401, 429, 500}) {
        f.status = status; f.response = "{\"ResponseMetadata\":{},\"Result\":\"0\"}";
        int before = f.calls.get();
        assertThrows(AuthFailure.class, () -> provider.verify(PHONE, "246810", REF, CODES));
        assertEquals(before + 1, f.calls.get());
      }
    }
  }

  @Test void malformedCodesAndOldModeReferencesNeverCallRemote() throws Exception {
    try (var f = new Fixture()) {
      var provider = provider(f.endpoint, Duration.ofSeconds(2));
      for (String code : new String[]{null, "", "bad", "12345", "1234567"})
        assertEquals(SmsOtpProvider.CheckResult.INVALID, provider.verify(PHONE, code, REF, CODES));
      assertEquals(SmsOtpProvider.CheckResult.EXPIRED, provider.verify(PHONE, "123456", CODES.hash(PHONE, "123456"), CODES));
      SmsOtpProvider local = (phone, code) -> {};
      assertEquals(SmsOtpProvider.CheckResult.INVALID, local.verify(PHONE, "123456", REF, CODES));
      assertEquals(0, f.calls.get());
    }
  }

  @Test void timeoutIsBoundedAndDoesNotRetryEitherAction() throws Exception {
    try (var f = new Fixture()) {
      f.delay = 1000;
      var provider = provider(f.endpoint, Duration.ofMillis(150));
      assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(AuthFailure.class, () -> provider.send(PHONE, REF)));
      assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(AuthFailure.class, () -> provider.verify(PHONE, "246810", REF, CODES)));
      assertEquals(2, f.calls.get());
    }
  }

  @Test void timeoutAlsoCoversStalledResponseBodyAfterHeaders() throws Exception {
    try (var f = new Fixture()) {
      f.bodyDelay = 1000;
      var provider = provider(f.endpoint, Duration.ofMillis(200));
      assertTimeoutPreemptively(Duration.ofMillis(800), () -> assertThrows(AuthFailure.class, () -> provider.send(PHONE, REF)));
      assertTimeoutPreemptively(Duration.ofMillis(800), () -> assertThrows(AuthFailure.class, () -> provider.verify(PHONE, "246810", REF, CODES)));
      assertEquals(2, f.calls.get());
    }
  }

  static VolcanoSmsOtpProvider provider(URI endpoint, Duration timeout) {
    return new VolcanoSmsOtpProvider("test-ak", "test-secret-never-print", "test-account", "测试签名", "test-template",
        endpoint, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build(), CLOCK, timeout);
  }
  record Captured(String method, String query, String body) {}
  static class Fixture implements AutoCloseable {
    final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final BlockingQueue<Captured> requests = new LinkedBlockingQueue<>();
    final AtomicInteger calls = new AtomicInteger();
    final URI endpoint;
    volatile String response = "{\"ResponseMetadata\":{},\"Result\":{\"MessageID\":[\"test-id\"]}}";
    volatile int status = 200;
    volatile long delay, bodyDelay;
    Fixture() throws Exception {
      endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
      server.setExecutor(executor);
      server.createContext("/", exchange -> {
        calls.incrementAndGet();
        requests.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getRawQuery(), new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        try {
          if (delay > 0) Thread.sleep(delay);
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Location", endpoint + "/redirected");
          exchange.sendResponseHeaders(status, bytes.length);
          if (bodyDelay > 0) {
            exchange.getResponseBody().write(bytes,0,1); exchange.getResponseBody().flush();
            Thread.sleep(bodyDelay); exchange.getResponseBody().write(bytes,1,bytes.length-1);
          } else exchange.getResponseBody().write(bytes);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally { exchange.close(); }
      });
      server.start();
    }
    public void close() { server.stop(0); executor.shutdownNow(); }
  }
}
