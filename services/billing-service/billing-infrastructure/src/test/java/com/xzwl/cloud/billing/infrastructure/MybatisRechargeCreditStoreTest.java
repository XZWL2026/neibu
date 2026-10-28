package com.xzwl.cloud.billing.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xzwl.cloud.billing.application.credit.AdminCreditCommand;
import com.xzwl.cloud.billing.application.credit.AdminCreditHandler;
import com.xzwl.cloud.billing.application.recharge.CreditPaidRechargeCommand;
import com.xzwl.cloud.billing.application.recharge.CreditPaidRechargeHandler;
import com.xzwl.cloud.billing.application.recharge.RechargeCreditResult;
import com.xzwl.cloud.billing.domain.money.Decimal4;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class MybatisRechargeCreditStoreTest {

  private static final Instant PAID_AT = Instant.parse("2026-09-07T08:00:00Z");
  @TempDir Path directory;
  private DriverManagerDataSource dataSource;
  private JdbcTemplate mysqlAdmin;
  private String mysqlTestDatabase;
  private JdbcTemplate sql;
  private CreditPaidRechargeHandler handler;

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
      String candidate = "billing_recharge_test_" + UUID.randomUUID().toString().replace("-", "");
      mysqlAdmin.execute("CREATE DATABASE " + candidate
          + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
      mysqlTestDatabase = candidate;
      dataSource = new DriverManagerDataSource(
          mysqlUrl + candidate + "?connectionTimeZone=UTC", username, password);
    }
    new ResourceDatabasePopulator(
        new ClassPathResource("db/migration/V1__administrator_credits.sql"),
        new ClassPathResource("db/migration/V2__paid_recharge_credits.sql"),
        new ClassPathResource("db/migration/V4__wallet_ledger_and_allocations.sql")).execute(dataSource);
    sql = new JdbcTemplate(dataSource);
    handler = new CreditPaidRechargeHandler(BillingTestStores.recharge(dataSource));
  }

  @AfterEach
  void removeOwnMysqlTestDatabase() {
    if (mysqlTestDatabase != null) mysqlAdmin.execute("DROP DATABASE " + mysqlTestDatabase);
  }

  @Test
  void paidRechargeUsesSnapshotRateAndPersistsBalanceRecordAndRealFundingLot() {
    RechargeCreditResult result = handler.handle(command("paid-1", "1.23459", "100.12349"));

    assertEquals(Decimal4.of("123.6023"), result.balance().availablePoints4());
    assertEquals(Decimal4.of("123.6023"), result.record().points4());
    assertEquals(Decimal4.of("100.1234"), result.record().rateSnapshot4());
    assertEquals(2, result.balance().version());
    assertEquals(1, count("recharge_credit_record"));
    assertEquals(0, count("admin_credit_record"));
    assertEquals("REAL_RECHARGE", sql.queryForObject(
        "SELECT lot_type FROM funding_lot WHERE lot_id = 'recharge:paid-1'", String.class));
    assertEquals(Decimal4.of("123.6023"), Decimal4.of(sql.queryForObject(
        "SELECT remaining_points4 FROM funding_lot WHERE lot_id = 'recharge:paid-1'",
        java.math.BigDecimal.class)));

    DriverManagerDataSource reopened = new DriverManagerDataSource(
        dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
    assertEquals(result, BillingTestStores.recharge(reopened).findByOrderId("paid-1").orElseThrow());
    assertEquals(result.balance(), BillingTestStores.admin(reopened).loadBalance("wallet-1"));
  }

  @Test
  void replayDoesNotDoubleCreditAndManualCreditStaysIndependent() {
    CreditPaidRechargeCommand command = command("paid-1", "2", "10");
    RechargeCreditResult original = handler.handle(command);
    new AdminCreditHandler(BillingTestStores.admin(dataSource)).handle(new AdminCreditCommand(
        "manual-1", "wallet-1", "key-1", Decimal4.of("7"), "admin-1", PAID_AT.plusSeconds(1), "手动加积分"));

    assertEquals(original, handler.handle(command));
    assertEquals(Decimal4.of("27"), BillingTestStores.admin(dataSource)
        .loadBalance("wallet-1").availablePoints4());
    assertEquals(1, count("recharge_credit_record"));
    assertEquals(1, count("admin_credit_record"));
    assertEquals(2, count("funding_lot"));
  }

  @Test
  void conflictingReplayAndInvalidAmountsDoNotChangeTheWallet() {
    RechargeCreditResult original = handler.handle(command("paid-1", "2", "10"));
    assertThrows(IllegalStateException.class, () -> handler.handle(command("paid-1", "3", "10")));
    assertThrows(IllegalStateException.class, () -> handler.handle(command("paid-1", "2", "11")));
    assertThrows(IllegalStateException.class, () -> handler.handle(new CreditPaidRechargeCommand(
        "paid-1", "other-user", "other-wallet", "other-key", Decimal4.of("2"),
        Decimal4.of("10"), PAID_AT)));
    assertThrows(IllegalArgumentException.class, () -> handler.handle(command("zero", "0", "10")));
    assertThrows(IllegalArgumentException.class, () -> handler.handle(command("negative", "-1", "10")));
    assertThrows(IllegalArgumentException.class, () -> handler.handle(command("bad-rate", "1", "0")));
    assertEquals(original.balance(), BillingTestStores.admin(dataSource).loadBalance("wallet-1"));
    assertEquals(1, count("wallet"));
    assertEquals(1, count("recharge_credit_record"));
  }

  @Test
  void fundingLotInsertFailureRollsBackBothTheRecordAndWalletUpdate() {
    sql.update("INSERT INTO wallet (wallet_id, available_points4, version) VALUES ('wallet-1', 0, 1)");
    sql.update("""
        INSERT INTO funding_lot
          (lot_id, wallet_id, lot_type, original_points4, remaining_points4, credited_at, benefit_eligible)
        VALUES ('recharge:paid-1', 'wallet-1', 'GIFT', 1, 1, ?, false)
        """, PAID_AT.toString());
    assertThrows(DataIntegrityViolationException.class,
        () -> handler.handle(command("paid-1", "2", "10")));
    assertEquals(Decimal4.zero(), BillingTestStores.admin(dataSource)
        .loadBalance("wallet-1").availablePoints4());
    assertEquals(1, BillingTestStores.admin(dataSource).loadBalance("wallet-1").version());
    assertEquals(0, count("recharge_credit_record"));
    assertEquals(1, count("funding_lot"));
  }

  @Test
  void concurrentCallbacksForOneOrderCreditOnlyOnce() throws Exception {
    var command = command("paid-1", "2", "10");
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> { start.await(); return handler.handle(command); });
      var second = pool.submit(() -> { start.await(); return handler.handle(command); });
      start.countDown();
      assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    }
    assertEquals(Decimal4.of("20"), BillingTestStores.admin(dataSource)
        .loadBalance("wallet-1").availablePoints4());
    assertEquals(1, count("recharge_credit_record"));
    assertEquals(1, count("funding_lot"));
  }

  @Test
  void concurrentDifferentOrdersDoNotLoseCredits() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> { start.await(); return handler.handle(command("paid-1", "2", "10")); });
      var second = pool.submit(() -> { start.await(); return handler.handle(command("paid-2", "3", "10")); });
      start.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    }
    assertEquals(Decimal4.of("50"), BillingTestStores.admin(dataSource)
        .loadBalance("wallet-1").availablePoints4());
    assertEquals(2, count("recharge_credit_record"));
    assertEquals(2, count("funding_lot"));
  }

  private int count(String table) {
    return sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }

  private static CreditPaidRechargeCommand command(String orderId, String amount, String rate) {
    return new CreditPaidRechargeCommand(orderId, "user-1", "wallet-1", "key-1",
        Decimal4.of(amount), Decimal4.of(rate), PAID_AT);
  }
}
