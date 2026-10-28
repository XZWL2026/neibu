package com.xzwl.cloud.billing.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xzwl.cloud.billing.application.recharge.CreditPaidRechargeCommand;
import com.xzwl.cloud.billing.application.recharge.CreditPaidRechargeHandler;
import com.xzwl.cloud.billing.application.recharge.SetRechargeRateHandler;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.billing.domain.recharge.RechargeRate;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class MybatisRechargeRateStoreTest {

  @TempDir Path directory;
  private DriverManagerDataSource dataSource;
  private JdbcTemplate mysqlAdmin;
  private String mysqlTestDatabase;
  private MybatisRechargeRateStore store;
  private SetRechargeRateHandler handler;

  @BeforeEach
  void openDatabase() {
    String mysqlUrl = System.getenv("BILLING_TEST_MYSQL_URL");
    if (mysqlUrl == null) {
      dataSource = new DriverManagerDataSource(
          "jdbc:h2:file:" + directory.resolve("billing") + ";MODE=MySQL", "sa", "");
    } else {
      if (!mysqlUrl.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/")) {
        throw new IllegalArgumentException("BILLING_TEST_MYSQL_URL must address local MySQL without a schema");
      }
      String username = System.getenv().getOrDefault("BILLING_TEST_MYSQL_USERNAME", "root");
      String password = System.getenv("BILLING_TEST_MYSQL_PASSWORD");
      mysqlAdmin = new JdbcTemplate(new DriverManagerDataSource(mysqlUrl, username, password));
      String candidate = "billing_rate_test_" + UUID.randomUUID().toString().replace("-", "");
      mysqlAdmin.execute("CREATE DATABASE " + candidate
          + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
      mysqlTestDatabase = candidate;
      dataSource = new DriverManagerDataSource(
          mysqlUrl + candidate + "?connectionTimeZone=UTC", username, password);
    }
    new ResourceDatabasePopulator(
        new ClassPathResource("db/migration/V1__administrator_credits.sql"),
        new ClassPathResource("db/migration/V2__paid_recharge_credits.sql"),
        new ClassPathResource("db/migration/V3__recharge_conversion_rate.sql"),
        new ClassPathResource("db/migration/V4__wallet_ledger_and_allocations.sql")).execute(dataSource);
    store = BillingTestStores.rate(dataSource);
    handler = new SetRechargeRateHandler(store);
  }

  @AfterEach
  void removeOwnMysqlTestDatabase() {
    if (mysqlTestDatabase != null) mysqlAdmin.execute("DROP DATABASE " + mysqlTestDatabase);
  }

  @Test
  void defaultIsTenAndAnUpdatePersistsWithoutChangingExistingOrderSnapshots() {
    RechargeRate original = store.load();
    assertEquals(new RechargeRate(Decimal4.of("10"), 1), original);
    var oldOrder = paidOrder("old-order", original.pointsPerYuan4());

    RechargeRate updated = handler.handle(Decimal4.of("25.12349"), original.version());
    assertEquals(new RechargeRate(Decimal4.of("25.1234"), 2), updated);
    var reopened = BillingTestStores.rate(new DriverManagerDataSource(
        dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword()));
    assertEquals(updated, reopened.load());

    var credit = new CreditPaidRechargeHandler(BillingTestStores.recharge(dataSource));
    assertEquals(Decimal4.of("20"), credit.handle(oldOrder).record().points4());
    assertEquals(Decimal4.of("50.2468"), credit.handle(
        paidOrder("new-order", reopened.load().pointsPerYuan4())).record().points4());
  }

  @Test
  void invalidRatesAndVersionsLeaveConfigurationUnchanged() {
    RechargeRate original = store.load();
    for (String value : new String[] {"0", "-1", "0.00009"}) {
      assertThrows(IllegalArgumentException.class, () -> handler.handle(Decimal4.of(value), 1));
    }
    assertThrows(IllegalArgumentException.class, () -> handler.handle(Decimal4.of("12"), 0));
    assertThrows(IllegalArgumentException.class,
        () -> handler.handle(Decimal4.of("10000000000000000000000000000000000"), 1));
    assertEquals(original, store.load());
  }

  @Test
  void staleUpdateCannotOverwriteANewerConfiguration() {
    RechargeRate updated = handler.handle(Decimal4.of("20"), 1);
    assertThrows(IllegalStateException.class, () -> handler.handle(Decimal4.of("30"), 1));
    assertEquals(updated, store.load());
  }

  @Test
  void concurrentUpdatesFromTheSameVersionHaveOneWinner() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> updateAfter(start, "20"));
      var second = pool.submit(() -> updateAfter(start, "30"));
      start.countDown();
      assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
    }
    assertEquals(2, store.load().version());
  }

  private int updateAfter(CountDownLatch start, String value) throws InterruptedException {
    start.await();
    try {
      handler.handle(Decimal4.of(value), 1);
      return 1;
    } catch (IllegalStateException staleVersion) {
      return 0;
    }
  }

  private static CreditPaidRechargeCommand paidOrder(String orderId, Decimal4 rateSnapshot4) {
    return new CreditPaidRechargeCommand(orderId, "user-1", "wallet-1", "key-1",
        Decimal4.of("2"), rateSnapshot4, Instant.parse("2026-09-07T08:00:00Z"));
  }
}
