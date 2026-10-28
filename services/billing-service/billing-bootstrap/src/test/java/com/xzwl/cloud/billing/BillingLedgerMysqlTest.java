package com.xzwl.cloud.billing;

import static org.junit.jupiter.api.Assertions.*;

import com.xzwl.cloud.billing.application.ledger.BillingLedgerStore;
import com.xzwl.cloud.billing.application.ledger.MediaBilling;
import com.xzwl.cloud.billing.domain.pricing.*;
import com.xzwl.cloud.billing.infrastructure.MybatisMediaBilling;
import com.xzwl.cloud.billing.infrastructure.persistence.BillingDatabase;
import com.xzwl.cloud.billing.application.credit.*;
import com.xzwl.cloud.billing.application.recharge.*;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "BILLING_TEST_MYSQL_URL", matches = ".+")
class BillingLedgerMysqlTest {
  @Test void precisionConcurrencyIdempotencyRefundAndRestart() throws Exception {
    String root = System.getenv("BILLING_TEST_MYSQL_URL");
    if (!root.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/")) throw new IllegalArgumentException("local database required");
    String database = "abp_billing_" + UUID.randomUUID().toString().replace("-", "");
    String user = System.getenv().getOrDefault("BILLING_TEST_MYSQL_USERNAME", "root");
    String password = System.getenv().getOrDefault("BILLING_TEST_MYSQL_PASSWORD", "");
    var admin = new JdbcTemplate(new DriverManagerDataSource(root, user, password));
    admin.execute("CREATE DATABASE " + database + " COLLATE utf8mb4_0900_bin");
    var properties = Map.<String,Object>of("spring.datasource.url", root + database,
        "spring.datasource.username", user, "spring.datasource.password", password,
        "abp.internal-token", "test-only-service-token-0000000000000000");
    try {
      try (var app = start(properties)) {
        var paid = app.getBean(CreditPaidRechargeHandler.class);
        var ledger = app.getBean(BillingLedgerStore.class);
        var command = new CreditPaidRechargeCommand("paid", "user", "key", "key",
            Decimal4.of("12.34569"), Decimal4.of("10.12349"), Instant.parse("2026-09-07T00:00:00Z"));
        race(8, () -> paid.handle(command));
        assertEquals(Decimal4.of("124.9794"), ledger.balance("key").availablePoints4());
        race(8, () -> ledger.consume("key", "task", Decimal4.of("20.1234")));
        assertEquals(Decimal4.of("104.8560"), ledger.balance("key").availablePoints4());
        race(8, () -> ledger.refund("key", "refund", "task", Decimal4.of("7.0123")));
        assertEquals(Decimal4.of("111.8683"), ledger.balance("key").availablePoints4());
        assertThrows(IllegalStateException.class, () -> ledger.refund("key", "refund", "task", Decimal4.of("1")));
        assertThrows(IllegalArgumentException.class, () -> ledger.refund("key", "excess", "task", Decimal4.of("14")));
        assertThrows(IllegalArgumentException.class, () -> ledger.consume("key", "overdraft", Decimal4.of("1000")));
        assertThrows(IllegalStateException.class, () -> ledger.reserveRechargeRefund("paid"));
        ledger.refund("key", "remainder", "task", Decimal4.of("13.1111"));
        race(4, () -> ledger.reserveRechargeRefund("paid"));
        assertEquals(Decimal4.zero(), ledger.balance("key").availablePoints4());
        var credit = app.getBean(AdminCreditHandler.class);
        var gift = new AdminCreditCommand("gift", "key", "key", Decimal4.of("4.5678"),
            "admin", Instant.EPOCH, "support");
        race(8, () -> credit.handle(gift));
        assertEquals(credit.handle(gift),credit.handle(new AdminCreditCommand("gift","key","key",
            Decimal4.of("4.5678"),"admin",Instant.now(),"support")));
        assertEquals(Decimal4.of("4.5678"), ledger.balance("key").availablePoints4());
        assertEquals(Decimal4.of("4.0000"), ledger.consume("key", "gift-spend", Decimal4.of("0.5678")).availablePoints4());
        assertTrue(ledger.entries("key", false, 100).stream().noneMatch(e -> e.kind().equals("ADMIN_CREDIT")));
        assertEquals(7, ledger.entries("key", true, 100).size());
      }
      try (var app = start(properties)) {
        var ledger = app.getBean(BillingLedgerStore.class);
        assertEquals(Decimal4.of("4.0000"), ledger.balance("key").availablePoints4());
        ledger.consume("key", "gift-spend", Decimal4.of("0.5678"));
        assertEquals(Decimal4.of("4.0000"), ledger.balance("key").availablePoints4());
      }
    } finally { admin.execute("DROP DATABASE " + database); }
  }
  @Test void mediaQuotesUseChargePricingWithoutWalletOrLedgerSideEffects() {
    String root = System.getenv("BILLING_TEST_MYSQL_URL");
    assertTrue(root.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"));
    String schema = "billing_quote_" + UUID.randomUUID().toString().replace("-", "");
    String user = System.getenv().getOrDefault("BILLING_TEST_MYSQL_USERNAME", "root");
    String password = System.getenv().getOrDefault("BILLING_TEST_MYSQL_PASSWORD", "");
    var sql = new JdbcTemplate(new DriverManagerDataSource(root, user, password));
    sql.execute("CREATE DATABASE " + schema);
    try (var app = start(Map.of("spring.datasource.url", root + schema,
        "spring.datasource.username", user, "spring.datasource.password", password,
        "abp.internal-token", "test-only-service-token-0000000000000000"))) {
      var ledger = app.getBean(BillingLedgerStore.class);
      app.getBean(CreditPaidRechargeHandler.class).handle(new CreditPaidRechargeCommand(
          "quote-paid", "owner", "key", "key", Decimal4.of("1000"), Decimal4.of("1"), Instant.now()));
      var prices = PricingCatalog.empty()
          .withPrice(new PricingEntry(new PricingKey("PERSONAL", "image", "2K"), Decimal4.of("1.23459")))
          .withPrice(new PricingEntry(new PricingKey("PERSONAL", "video", "720p"), Decimal4.of("0.12349")))
          .withPrice(new PricingEntry(new PricingKey("PERSONAL", "free", "2K"), Decimal4.zero()))
          .withPrice(new PricingEntry(new PricingKey("TEAM", "private", "2K"), Decimal4.of("8")))
          .withPrice(new PricingEntry(new PricingKey("ENTERPRISE", "image", "2K"), Decimal4.of("0.5")));
      var rates = app.getBean(RechargeRateStore.class);
      rates.save(new com.xzwl.cloud.billing.domain.recharge.RechargeRate(Decimal4.of("10.12349"), rates.load().version()+1));
      var accountType = new java.util.concurrent.atomic.AtomicReference<>("PERSONAL");
      var media = new MybatisMediaBilling(app.getBean(BillingDatabase.class),
          owner -> new MediaBilling.Key("key", owner, accountType.get(), "MEDIA", true), prices, rates, ledger);
      var balance = ledger.balance("key");
      var entries = ledger.entries("key", true, 100);
      var image = media.quote(new MediaBilling.QuoteRequest("owner", "IMAGE", "image", "2K", 3, 0));
      var video = media.quote(new MediaBilling.QuoteRequest("owner", "VIDEO", "video", "720p", 1, 5));
      assertEquals(Decimal4.of("1.2345").multiply(Decimal4.of("3")).asBigDecimal(), image.amount());
      assertEquals(Decimal4.of("1.2345").multiply(Decimal4.of("3")).multiply(Decimal4.of("10.1234")).asBigDecimal(), image.points());
      assertEquals(Decimal4.of("0.1234").multiply(Decimal4.of("5")).multiply(Decimal4.of("10.1234")).asBigDecimal(), video.points());
      assertEquals(Decimal4.zero().asBigDecimal(), media.quote(new MediaBilling.QuoteRequest("owner", "IMAGE", "free", "2K", 1, 0)).points());
      assertThrows(IllegalArgumentException.class, () -> media.quote(new MediaBilling.QuoteRequest("owner", "IMAGE", "missing", "2K", 1, 0)));
      assertEquals(List.of("free", "image", "video"), media.availablePrices("owner").stream().map(MediaBilling.AvailablePrice::model).toList());
      assertEquals(balance, ledger.balance("key"));
      assertEquals(entries, ledger.entries("key", true, 100));
      var debit = media.charge(new MediaBilling.Charge("quote-charge", "owner", "IMAGE", "image", "2K", 3, 0));
      assertEquals(image.amount(), debit.amount());
      assertEquals(image.rate(), debit.rate());
      assertEquals(image.points(), debit.points());
      assertEquals(debit, media.charge(new MediaBilling.Charge("quote-charge", "owner", "IMAGE", "image", "2K", 3, 0)));
      accountType.set("ENTERPRISE");
      assertEquals(debit, media.charge(new MediaBilling.Charge("quote-charge", "owner", "IMAGE", "image", "2K", 3, 0)));
      var enterprise = media.quote(new MediaBilling.QuoteRequest("owner", "IMAGE", "image", "2K", 3, 0));
      assertEquals(Decimal4.of("1.5").asBigDecimal(), enterprise.amount());
      var next = media.charge(new MediaBilling.Charge("enterprise-charge", "owner", "IMAGE", "image", "2K", 3, 0));
      assertEquals(enterprise.points(), next.points());
      accountType.set("PERSONAL");
      assertEquals(next, media.charge(new MediaBilling.Charge("enterprise-charge", "owner", "IMAGE", "image", "2K", 3, 0)));
      assertEquals(image, media.quote(new MediaBilling.QuoteRequest("owner", "IMAGE", "image", "2K", 3, 0)));
      rates.save(new com.xzwl.cloud.billing.domain.recharge.RechargeRate(Decimal4.of("99"), rates.load().version()+1));
      var charged = ledger.entries("key", false, 100).stream().filter(e -> e.requestId().equals("quote-charge")).findFirst().orElseThrow();
      assertEquals("-3.7035", charged.amount4());
      assertEquals(debit.points().negate().toPlainString(), charged.points4());
      ledger.consume("key", "legacy-no-receipt", Decimal4.of("1"));
      assertNull(ledger.entries("key", false, 1).getFirst().amount4());
      app.getBean(CreditPaidRechargeHandler.class).handle(new CreditPaidRechargeCommand(
          "other-paid", "other-owner", "other-key", "other-key", Decimal4.of("10"), Decimal4.of("1"), Instant.now()));
      ledger.consume("other-key", "quote-charge", Decimal4.of("1"));
      assertNull(ledger.entries("other-key", false, 1).getFirst().amount4());
    } finally { sql.execute("DROP DATABASE " + schema); }
  }

  @Test void adminDebitIsAtomicAuditedAndUsesOriginalRateOnReplay() throws Exception {
    String root = System.getenv("BILLING_TEST_MYSQL_URL");
    assertTrue(root.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"));
    String schema = "billing_debit_" + UUID.randomUUID().toString().replace("-", "");
    String user = System.getenv().getOrDefault("BILLING_TEST_MYSQL_USERNAME", "root");
    String password = System.getenv().getOrDefault("BILLING_TEST_MYSQL_PASSWORD", "");
    var sql = new JdbcTemplate(new DriverManagerDataSource(root, user, password));
    sql.execute("CREATE DATABASE " + schema);
    var properties = Map.<String,Object>of("spring.datasource.url", root + schema,
        "spring.datasource.username", user, "spring.datasource.password", password,
        "abp.internal-token", "test-only-service-token-0000000000000000");
    try {
      BillingLedgerStore.Adjustment first;
      try (var app = start(properties)) {
        var ledger = app.getBean(BillingLedgerStore.class);
        var rates = app.getBean(RechargeRateStore.class);
        rates.save(new com.xzwl.cloud.billing.domain.recharge.RechargeRate(Decimal4.of("10.1234"), rates.load().version()+1));
        var paid = app.getBean(CreditPaidRechargeHandler.class);
        paid.handle(new CreditPaidRechargeCommand("debit-paid", "owner", "debit-key", "debit-key", Decimal4.of("10"), Decimal4.of("10"), Instant.EPOCH));
        var command = new BillingLedgerStore.Debit("admin-debit", "debit-key", "owner", "admin", new java.math.BigDecimal("1.23"), "account correction");
        race(8, () -> ledger.debit(command));
        assertEquals(Decimal4.of("87.5483"), ledger.balance("debit-key").availablePoints4());
        first = ledger.adjustments("debit-key").getFirst();
        assertEquals("ADMIN_DEBIT", first.kind());
        assertEquals("12.4517", first.points4());
        assertEquals("1.2300", first.amount4());
        assertEquals("10.1234", first.rateSnapshot4());
        assertEquals("admin", first.operatorId());
        assertEquals("owner", first.userId());
        rates.save(new com.xzwl.cloud.billing.domain.recharge.RechargeRate(Decimal4.of("2"), rates.load().version()+1));
        assertEquals(Decimal4.of("87.5483"), ledger.debit(command).availablePoints4());
        assertEquals(first, ledger.adjustments("debit-key").getFirst());
        assertThrows(IllegalStateException.class, () -> ledger.debit(new BillingLedgerStore.Debit("admin-debit", "debit-key", "owner", "other-admin", new java.math.BigDecimal("1.23"), "account correction")));
        assertThrows(IllegalStateException.class, () -> ledger.debit(new BillingLedgerStore.Debit("admin-debit", "debit-key", "owner", "admin", new java.math.BigDecimal("1.24"), "account correction")));
        assertThrows(IllegalStateException.class, () -> ledger.debit(new BillingLedgerStore.Debit("admin-debit", "debit-key", "owner", "admin", new java.math.BigDecimal("1.23"), "changed remark")));
        assertThrows(IllegalStateException.class, () -> ledger.debit(new BillingLedgerStore.Debit("admin-debit", "another-key", "owner", "admin", new java.math.BigDecimal("1.23"), "account correction")));
        for (String amount : List.of("0", "-1", "0.001", "1.2301", "10000000000000000000000000000000000"))
          assertThrows(IllegalArgumentException.class, () -> ledger.debit(new BillingLedgerStore.Debit("invalid", "debit-key", "owner", "admin", new java.math.BigDecimal(amount), "invalid amount")));
        assertThrows(IllegalArgumentException.class, () -> ledger.debit(new BillingLedgerStore.Debit("too-much", "debit-key", "owner", "admin", new java.math.BigDecimal("100"), "overdraft")));
        // An admin debit must not become refundable through the media-consumption endpoint.
        assertThrows(IllegalArgumentException.class, () -> ledger.refund("debit-key", "fake-refund", "admin-debit", Decimal4.of("1")));
        assertThrows(IllegalStateException.class, () -> ledger.reserveRechargeRefund("debit-paid"));
        paid.handle(new CreditPaidRechargeCommand("frozen-paid", "owner", "frozen-key", "frozen-key", Decimal4.of("10"), Decimal4.of("10"), Instant.EPOCH));
        ledger.reserveRechargeRefund("frozen-paid");
        assertThrows(IllegalArgumentException.class, () -> ledger.debit(new BillingLedgerStore.Debit("frozen-debit", "frozen-key", "owner", "admin", new java.math.BigDecimal("0.01"), "frozen funds")));
        assertEquals(Decimal4.zero(), ledger.balance("frozen-key").availablePoints4());
        assertTrue(ledger.adjustments("frozen-key").isEmpty());
        assertEquals(1, ledger.adjustments("debit-key").size());
        assertEquals(2, ledger.entries("debit-key", true, 100).size());
        paid.handle(new CreditPaidRechargeCommand("race-funding", "owner", "race-key", "race-key", Decimal4.of("2"), Decimal4.of("1"), Instant.EPOCH));
        var successes = new java.util.concurrent.atomic.AtomicInteger();
        race(8, () -> {
          try {
            ledger.debit(new BillingLedgerStore.Debit(UUID.randomUUID().toString(), "race-key", "owner", "admin", new java.math.BigDecimal("0.60"), "concurrent debit"));
            successes.incrementAndGet();
          } catch (IllegalArgumentException insufficient) { assertEquals("insufficient balance", insufficient.getMessage()); }
          return null;
        });
        assertEquals(1, successes.get());
        assertEquals(Decimal4.of("0.8"), ledger.balance("race-key").availablePoints4());
        assertEquals(1, ledger.adjustments("race-key").size());
        var jdbc = app.getBean(JdbcTemplate.class);
        assertEquals(new java.math.BigDecimal("0.8000"), jdbc.queryForObject("SELECT SUM(remaining_points4) FROM funding_lot WHERE wallet_id='race-key'", java.math.BigDecimal.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM funding_allocation WHERE wallet_id='race-key' AND kind='ADMIN_DEBIT'", Integer.class));
        var ownership = (com.xzwl.cloud.billing.application.ledger.KeyOwnershipPort)(id -> new com.xzwl.cloud.billing.application.ledger.KeyOwnershipPort.Key(id,"owner",true));
        var controller = new com.xzwl.cloud.billing.interfaces.http.BillingCommandController(ledger,ownership,
            app.getBean(AdminCreditHandler.class),app.getBean(AdminCreditStore.class),paid,rates);
        var wallets = new com.xzwl.cloud.billing.interfaces.http.BillingWalletController(ledger,ownership);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller,wallets)
            .setControllerAdvice(new com.xzwl.cloud.billing.interfaces.http.BillingErrors()).build();
        var body = "{\"requestId\":\"http-debit\",\"keyId\":\"debit-key\",\"amount4\":\"0.01\",\"remark\":\"HTTP correction\"}";
        var post = "/api/v1/admin/billing/debits";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(post).contentType("application/json").content(body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(post).principal(()->"owner").contentType("application/json").content(body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        for (String role : List.of("ADMIN", "NORMAL_ADMIN")) {
          mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(post).principal(()->"admin").with(req->{req.addUserRole(role);return req;}).contentType("application/json").content(body))
              .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(post).principal(()->"other-admin").with(req->{req.addUserRole("ADMIN");return req;}).contentType("application/json").content(body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(post).principal(()->"admin").with(req->{req.addUserRole("ADMIN");return req;}).contentType("application/json").content(body.replace("0.01","0.001")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(post).principal(()->"admin").with(req->{req.addUserRole("ADMIN");return req;}).contentType("application/json").content(body.replace("http-debit","http-overdraft").replace("0.01","100")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(post).principal(()->"admin").with(req->{req.addUserRole("ADMIN");return req;}).contentType("application/json").content(body.replace("HTTP correction"," ")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/billing/wallets/debit-key/adjustments").principal(()->"owner"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/billing/wallets/debit-key/adjustments").principal(()->"admin").with(req->{req.addUserRole("NORMAL_ADMIN");return req;}))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(result -> assertEquals("ADMIN_DEBIT", new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString()).get(0).get("kind").asText()));
      }
      try (var app = start(properties)) {
        var ledger = app.getBean(BillingLedgerStore.class);
        assertEquals(first, ledger.adjustments("debit-key").get(1));
        assertEquals(Decimal4.of("87.5483"), ledger.debit(new BillingLedgerStore.Debit("admin-debit", "debit-key", "owner", "admin", new java.math.BigDecimal("1.2300"), "account correction")).availablePoints4());
        assertEquals(Decimal4.of("87.5283"), ledger.balance("debit-key").availablePoints4());
      }
    } finally { sql.execute("DROP DATABASE " + schema); }
  }

  static void race(int count, Callable<?> work) throws Exception {
    var gate = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(count)) {
      var futures = new ArrayList<Future<?>>();
      for (int i=0;i<count;i++) futures.add(pool.submit(() -> { gate.await(); return work.call(); }));
      gate.countDown();
      for (var f : futures) f.get(20, TimeUnit.SECONDS);
    }
  }
  static ConfigurableApplicationContext start(Map<String,Object> properties) {
    var env = new StandardEnvironment();
    env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    env.getPropertySources().addFirst(new MapPropertySource("test", properties));
    return new SpringApplicationBuilder(TemplateApplication.class).environment(env).web(WebApplicationType.NONE)
        .run("--spring.config.location=classpath:/application.yml");
  }
}
