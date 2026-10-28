package com.xzwl.cloud.iam;

import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.iam.application.account.*;
import com.xzwl.cloud.iam.application.otp.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.xzwl.cloud.iam.infrastructure.account.MyBatisOtpStore;
import com.xzwl.cloud.iam.infrastructure.account.PhoneOtpMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="IAM_TEST_MYSQL_URL", matches=".+")
class OtpPolicyMysqlTest {
  @Test void exactExpiryAndResendBoundariesAndSingleConsumption() {
    try (Fixture f = new Fixture()) {
      f.otp.send("13800000001");
      String first = f.codes.get("+8613800000001");
      assertTrue(first.matches("[0-9]{6}"));
      assertNotEquals(first, f.db.jdbc.queryForObject("SELECT code_hash FROM iam_phone_otp", String.class));
      f.time.advance(59_999); rejected(429, () -> f.otp.send("+8613800000001"));
      f.time.advance(1); f.otp.send("+8613800000001");
      rejected(401, () -> f.otp.login("13800000001", first));
      String current = f.codes.get("+8613800000001");
      f.time.advance(299_999);
      assertEquals("USER", f.otp.login("13800000001", current).role());
      assertNull(f.db.jdbc.queryForObject("SELECT code_hash FROM iam_phone_otp", String.class));
      rejected(401, () -> f.otp.login("13800000001", current));
      f.otp.send("13800000002");
      f.time.advance(300_000);
      rejected(401, () -> f.otp.login("13800000002", f.codes.get("+8613800000002")));
    }
  }
  @Test void fifthIncorrectCodeLocksOnlyCurrentChallengeIncludingMalformedAttempts() {
    try (Fixture f = new Fixture()) {
      f.otp.send("13800000001");
      for (int i=0;i<4;i++) rejected(401, () -> f.otp.login("13800000001", "bad"));
      rejected(423, () -> f.otp.login("13800000001", "bad"));
      rejected(423, () -> f.otp.login("13800000001", f.codes.get("+8613800000001")));
      assertEquals(5, f.db.jdbc.queryForObject("SELECT failures FROM iam_phone_otp", Integer.class));
      f.time.advance(60_000); f.otp.send("13800000001");
      assertNotNull(f.otp.login("13800000001", f.codes.get("+8613800000001")));
    }
  }
  @Test void dailyQuotaUsesShanghaiMidnightAndDoesNotBypassResendInterval() {
    try (Fixture f = new Fixture()) {
      for (int i=0;i<5;i++) { f.otp.send("13800000002"); f.time.advance(60_000); }
      assertEquals("OTP_DAILY_LIMIT", assertThrows(AuthFailure.class, () -> f.otp.send("+8613800000002")).getMessage());
      f.time.now = Instant.parse("2026-09-07T15:55:30Z");
      for (int i=0;i<5;i++) { f.otp.send("13800000001"); f.time.advance(60_000); }
      f.time.now = Instant.parse("2026-09-07T15:59:59Z");
      rejected(429, () -> f.otp.send("13800000001"));
      f.time.now = Instant.parse("2026-09-07T16:00:00Z");
      rejected(429, () -> f.otp.send("13800000001"));
      f.time.advance(30_000); f.otp.send("13800000001");
      assertEquals(1, f.db.jdbc.queryForObject("SELECT sent_count FROM iam_phone_otp WHERE phone='+8613800000001'", Integer.class));
      assertEquals(LocalDate.of(2026,9,8), f.db.jdbc.queryForObject("SELECT sent_day FROM iam_phone_otp WHERE phone='+8613800000001'", LocalDate.class));
    }
  }
  @Test void concurrentInstancesCannotExceedSendOrVerificationLimits() throws Exception {
    try (Fixture f = new Fixture()) {
      var sent = new AtomicInteger();
      parallel(12, () -> { try { f.otp.send("13800000001"); sent.incrementAndGet(); } catch (AuthFailure e) { assertEquals(429,e.status()); } });
      assertEquals(1, sent.get());
      var other = f.service(f.provider);
      var logins = new AtomicInteger();
      parallel(12, () -> { try { other.login("13800000001",f.codes.get("+8613800000001")); logins.incrementAndGet(); } catch (AuthFailure e) { assertEquals(401,e.status()); } });
      assertEquals(1, logins.get());
      f.time.advance(60_000); other.send("13800000001");
      parallel(12, () -> { try { f.otp.login("13800000001", "bad"); fail(); } catch (AuthFailure e) { assertTrue(e.status()==401 || e.status()==423); } });
      assertEquals(5,f.db.jdbc.queryForObject("SELECT failures FROM iam_phone_otp",Integer.class));
    }
  }
  @Test void reservationCommitsBeforeSmsAndProviderFailureCannotResetQuota() {
    try (Fixture f = new Fixture()) {
      var observed = new java.util.concurrent.atomic.AtomicBoolean();
      OtpService failing = f.service((phone, code) -> {
        // A second connection must observe the reservation before delivery occurs.
        assertEquals(1, f.db.jdbc.queryForObject("SELECT sent_count FROM iam_phone_otp WHERE phone=?", Integer.class, phone));
        assertTrue(f.db.jdbc.queryForObject("SELECT consumed FROM iam_phone_otp WHERE phone=?", Boolean.class, phone));
        observed.set(true);
        throw new IllegalStateException("delivery failed");
      });
      rejected(503, () -> failing.send("13800000001"));
      assertTrue(observed.get(), "SMS must only be called after the quota reservation is committed");
      rejected(429, () -> f.otp.send("13800000001"));
      assertNull(f.db.jdbc.queryForObject("SELECT code_hash FROM iam_phone_otp",String.class));
      assertEquals(1,f.db.jdbc.queryForObject("SELECT sent_count FROM iam_phone_otp",Integer.class));
    }
  }
  @Test void delayedDeliveryCannotReportAnExpiredOrReplacedCodeAsSuccessful() {
    try (Fixture f = new Fixture()) {
      var slow = f.service((phone,code) -> { f.codes.put(phone,code); f.time.advance(300_000); });
      AuthFailure expired = assertThrows(AuthFailure.class, () -> slow.send("13800000001"));
      assertEquals(503, expired.status());
      assertEquals("OTP_DELIVERY_EXPIRED_OR_REPLACED", expired.getMessage());
      rejected(401, () -> f.otp.login("13800000001", f.codes.get("+8613800000001")));
      var superseded = f.service((phone,code) -> { f.time.advance(60_000); f.otp.send(phone); });
      AuthFailure replaced = assertThrows(AuthFailure.class, () -> superseded.send("13800000002"));
      assertEquals("OTP_DELIVERY_EXPIRED_OR_REPLACED", replaced.getMessage());
      assertNotNull(f.otp.login("13800000002", f.codes.get("+8613800000002")));
    }
  }
  @Test void managedVerificationPreservesLimitsExpiryFailuresAndModeIsolation() {
    try (Fixture f = new Fixture()) {
      var sms = new ManagedSms(); var otp = f.service(sms);
      otp.send("13800000001");
      assertTrue(f.db.jdbc.queryForObject("SELECT code_hash FROM iam_phone_otp", String.class).startsWith("volc:"));
      rejected(429, () -> otp.send("+8613800000001"));
      for (int i=0; i<4; i++) rejected(401, () -> otp.login("13800000001", "bad"));
      rejected(423, () -> otp.login("13800000001", "bad"));
      rejected(423, () -> otp.login("13800000001", sms.code("+8613800000001")));
      f.time.advance(60_000); otp.send("13800000001");
      sms.unavailable = true;
      rejected(503, () -> otp.login("13800000001", sms.code("+8613800000001")));
      assertEquals(0, f.db.jdbc.queryForObject("SELECT failures FROM iam_phone_otp", Integer.class));
      sms.unavailable = false; sms.expired = true;
      rejected(401, () -> otp.login("13800000001", sms.code("+8613800000001")));
      assertNull(f.db.jdbc.queryForObject("SELECT code_hash FROM iam_phone_otp", String.class));
      int calls = sms.checks.get(); sms.expired = false;
      rejected(401, () -> otp.login("13800000001", sms.code("+8613800000001")));
      assertEquals(calls, sms.checks.get());
      f.time.advance(60_000); otp.send("13800000001");
      rejected(401, () -> f.otp.login("13800000001", sms.code("+8613800000001")));
      f.time.advance(60_000); f.otp.send("13800000001");
      rejected(401, () -> otp.login("13800000001", f.codes.get("+8613800000001")));
      f.time.advance(60_000); otp.send("13800000001");
      f.time.advance(300_000);
      rejected(401, () -> otp.login("13800000001", sms.code("+8613800000001")));
      rejected(429, () -> otp.send("13800000001"));
    }
  }

  @Test void managedProviderCannotExtendLocalExpiryDuringVerification() {
    try (Fixture f = new Fixture()) {
      var sms = new ManagedSms(); var otp = f.service(sms);
      otp.send("13800000001");
      sms.afterCheck = () -> f.time.advance(300_000);
      rejected(401, () -> otp.login("13800000001", sms.code("+8613800000001")));
      assertNull(f.db.jdbc.queryForObject("SELECT code_hash FROM iam_phone_otp", String.class));
    }
  }

  @Test void managedScenesSurviveServiceRestartAndConcurrentPhonesWithoutReplay() throws Exception {
    try (Fixture f = new Fixture()) {
      var sms = new ManagedSms(); var otp = f.service(sms);
      try (var pool=Executors.newFixedThreadPool(2)) {
        var first=pool.submit(() -> otp.send("13800000001"));
        var second=pool.submit(() -> otp.send("13800000002"));
        first.get(10,TimeUnit.SECONDS); second.get(10,TimeUnit.SECONDS);
      }
      assertNotEquals(sms.references.get("+8613800000001"), sms.references.get("+8613800000002"));
      var restarted = f.service(sms); var logins = new AtomicInteger();
      parallel(12, () -> { try { restarted.login("13800000001", sms.code("+8613800000001")); logins.incrementAndGet(); }
        catch (AuthFailure e) { assertEquals(401,e.status()); } });
      assertEquals(1, logins.get()); assertEquals(1, sms.checks.get());
      assertNotNull(restarted.login("13800000002", sms.code("+8613800000002")));
      f.time.advance(60_000); otp.send("13800000001");
      String previous = sms.code("+8613800000001");
      f.time.advance(60_000); restarted.send("13800000001");
      rejected(401, () -> restarted.login("13800000001", previous));
      assertNotNull(restarted.login("13800000001", sms.code("+8613800000001")));
    }
  }

  @Test void managedLateSendCannotActivateReplacedReferenceAndSendFailureKeepsQuota() {
    try (Fixture f = new Fixture()) {
      var sms = new ManagedSms(); var otp = f.service(sms);
      sms.afterSend = (phone, reference) -> {
        sms.afterSend = (p,r) -> {};
        f.time.advance(60_000); otp.send(phone);
      };
      assertEquals("OTP_DELIVERY_EXPIRED_OR_REPLACED", assertThrows(AuthFailure.class, () -> otp.send("13800000001")).getMessage());
      assertNotNull(otp.login("13800000001", sms.code("+8613800000001")));
      sms.unavailable = true;
      rejected(503, () -> otp.send("13800000002"));
      rejected(429, () -> otp.send("13800000002"));
      assertNull(f.db.jdbc.queryForObject("SELECT code_hash FROM iam_phone_otp WHERE phone='+8613800000002'", String.class));
    }
  }

  private static class ManagedSms implements SmsOtpProvider {
    final Map<String,String> references = new ConcurrentHashMap<>(), issued = new ConcurrentHashMap<>();
    final AtomicInteger sequence = new AtomicInteger(100000), checks = new AtomicInteger();
    boolean unavailable, expired;
    Runnable afterCheck = () -> {};
    java.util.function.BiConsumer<String,String> afterSend = (phone,ref) -> {};
    public String prepareCode(String ignored) { return "volc:" + UUID.randomUUID(); }
    public String reference(String phone, String prepared, OtpCodes codes) { return prepared; }
    public void send(String phone, String reference) {
      if (unavailable) throw new AuthFailure(503, "SMS_PROVIDER_UNAVAILABLE");
      references.put(phone,reference); issued.put(phone+reference,Integer.toString(sequence.incrementAndGet()));
      afterSend.accept(phone,reference);
    }
    String code(String phone) { return issued.get(phone+references.get(phone)); }
    public CheckResult verify(String phone, String code, String reference, OtpCodes codes) {
      checks.incrementAndGet();
      if (unavailable) throw new AuthFailure(503, "SMS_PROVIDER_UNAVAILABLE");
      if (expired || !reference.startsWith("volc:")) return CheckResult.EXPIRED;
      afterCheck.run();
      return Objects.equals(code,issued.get(phone+reference)) ? CheckResult.VALID : CheckResult.INVALID;
    }
  }
  static void rejected(int code, Runnable action) { assertEquals(code, assertThrows(AuthFailure.class, action::run).status()); }
  static void parallel(int count, Runnable action) throws Exception {
    try (var pool=Executors.newFixedThreadPool(count)) {
      var start=new CountDownLatch(1); var jobs=new ArrayList<Future<?>>();
      for(int i=0;i<count;i++) jobs.add(pool.submit(() -> { start.await(); action.run(); return null; }));
      start.countDown(); for(var job:jobs) job.get(30,TimeUnit.SECONDS);
    }
  }
  static final class MutableClock extends Clock {
    volatile Instant now=Instant.parse("2026-09-07T00:00:00Z");
    void advance(long millis) { now=now.plusMillis(millis); }
    public ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(ZoneId zone) { return this; }
    public Instant instant() { return now; }
  }
  private static final class Fixture implements AutoCloseable {
    final ReleaseAuthMysqlTest.Database db=new ReleaseAuthMysqlTest.Database();
    final MutableClock time=new MutableClock();
    final Map<String,String> codes=new ConcurrentHashMap<>();
    final SmsOtpProvider provider=codes::put;
    final OtpService otp;
    Fixture() {
      Flyway.configure().dataSource(db.url,"root","").locations("classpath:db/migration").load().migrate();
      otp=service(provider);
    }
    OtpService service(SmsOtpProvider sms) {
      var ds=new DriverManagerDataSource(db.url,"root","");
      var configuration=new MybatisConfiguration(new Environment("otp-test",new SpringManagedTransactionFactory(),ds));
      configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
      configuration.addMapper(PhoneOtpMapper.class);
      var sessions=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
      var store=new MyBatisOtpStore(sessions.getMapper(PhoneOtpMapper.class),new TransactionTemplate(new DataSourceTransactionManager(ds)));
      var directory=new UserDirectory() {
        public UserIdentity resolve(String phone) { return new UserIdentity(phone,phone,"user",true,0); }
        public UserIdentity find(String id) { return resolve(id); }
      };
      return new OtpService(store,sms,new OtpCodes(System.getenv("IAM_OTP_HMAC_KEY")),directory,time);
    }
    public void close() { db.close(); }
  }
}
