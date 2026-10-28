package com.xzwl.cloud.billing.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xzwl.cloud.billing.application.credit.AdminCreditCommand;
import com.xzwl.cloud.billing.application.credit.AdminCreditHandler;
import com.xzwl.cloud.billing.application.credit.AdminCreditRecord;
import com.xzwl.cloud.billing.application.credit.AdminCreditResult;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.billing.domain.wallet.WalletBalance;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class MybatisAdminCreditStoreTest {

  @TempDir Path directory;
  private DriverManagerDataSource dataSource;
  private JdbcTemplate mysqlAdmin;
  private String mysqlTestDatabase;
  private JdbcTemplate sql;
  private MybatisAdminCreditStore store;

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
      String candidate = "billing_credit_test_" + UUID.randomUUID().toString().replace("-", "");
      mysqlAdmin.execute("CREATE DATABASE " + candidate
          + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
      // Only schemas successfully created by this test may be removed in teardown.
      mysqlTestDatabase = candidate;
      dataSource = new DriverManagerDataSource(
          mysqlUrl + mysqlTestDatabase + "?connectionTimeZone=UTC", username, password);
    }
    new ResourceDatabasePopulator(
        new ClassPathResource("db/migration/V1__administrator_credits.sql"),
        new ClassPathResource("db/migration/V2__paid_recharge_credits.sql"),
        new ClassPathResource("db/migration/V4__wallet_ledger_and_allocations.sql"))
        .execute(dataSource);
    sql = new JdbcTemplate(dataSource);
    sql.update("INSERT INTO wallet (wallet_id, available_points4, version) VALUES (?, ?, ?)",
        "wallet-1", Decimal4.zero().asBigDecimal(), 1);
    store = BillingTestStores.admin(dataSource);
  }

  @AfterEach
  void removeOwnMysqlTestDatabase() {
    if (mysqlTestDatabase != null) mysqlAdmin.execute("DROP DATABASE " + mysqlTestDatabase);
  }

  @Test
  void reconnectReadsBalanceAndTheOriginalIdempotentResult() {
    AdminCreditCommand firstCommand = command("request-1", "12.34569");
    AdminCreditResult first = new AdminCreditHandler(store).handle(firstCommand);
    new AdminCreditHandler(store).handle(command("request-2", "2.0000"));

    DriverManagerDataSource reconnected = new DriverManagerDataSource(
        dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
    MybatisAdminCreditStore reloaded = BillingTestStores.admin(reconnected);
    AdminCreditResult replay = new AdminCreditHandler(reloaded).handle(firstCommand);

    assertEquals(first, replay);
    assertEquals(Decimal4.of("12.3456"), replay.balance().availablePoints4());
    assertEquals(Decimal4.of("14.3456"), reloaded.loadBalance("wallet-1").availablePoints4());
    assertEquals(firstCommand.at(), replay.record().at());
    assertEquals(2, recordCount());
  }

  @Test
  void duplicateRecordInsertRollsBackTheBalanceUpdate() {
    AdminCreditResult first = new AdminCreditHandler(store).handle(command("request-1", "3"));

    assertThrows(DataIntegrityViolationException.class,
        () -> store.saveAtomically(first.record(), first.balance().credit(Decimal4.of("3"))));

    assertEquals(first.balance(), store.loadBalance("wallet-1"));
    assertEquals(first, store.findByRequestId("request-1").orElseThrow());
    assertEquals(1, recordCount());
  }

  @Test
  void conflictingReplayAndNonPositiveAmountsDoNotChangeTheDatabase() {
    AdminCreditHandler handler = new AdminCreditHandler(store);
    AdminCreditResult first = handler.handle(command("request-1", "4"));

    assertThrows(IllegalStateException.class, () -> handler.handle(command("request-1", "5")));
    assertThrows(IllegalArgumentException.class, () -> handler.handle(command("zero", "0")));
    assertThrows(IllegalArgumentException.class, () -> handler.handle(command("negative", "-1")));

    assertEquals(first.balance(), store.loadBalance("wallet-1"));
    assertEquals(1, recordCount());
  }

  @Test
  void parallelStaleWritesCannotOverwriteEachOtherAndTheLoserCanRetry() throws Exception {
    WalletBalance initial = store.loadBalance("wallet-1");
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(attempt(start, command("parallel-1", "1"), initial));
      var second = executor.submit(attempt(start, command("parallel-2", "2"), initial));
      start.countDown();
      boolean firstWritten = first.get(10, TimeUnit.SECONDS);
      boolean secondWritten = second.get(10, TimeUnit.SECONDS);
      assertTrue(firstWritten ^ secondWritten, "one stale writer must be rejected");
      assertEquals(1, recordCount());

      new AdminCreditHandler(store).handle(
          firstWritten ? command("parallel-2", "2") : command("parallel-1", "1"));
    }
    assertEquals(new WalletBalance("wallet-1", Decimal4.of("3"), 3), store.loadBalance("wallet-1"));
    assertEquals(2, recordCount());
  }

  @Test
  void mismatchedWalletOrAmountCannotBePersisted() {
    AdminCreditRecord record = record(command("request-1", "3"));
    assertThrows(IllegalArgumentException.class,
        () -> store.saveAtomically(record, WalletBalance.zero("other-wallet").credit(Decimal4.of("3"))));
    assertThrows(OptimisticLockingFailureException.class,
        () -> store.saveAtomically(record, WalletBalance.zero("wallet-1").credit(Decimal4.of("4"))));

    assertEquals(WalletBalance.zero("wallet-1"), store.loadBalance("wallet-1"));
    assertEquals(0, recordCount());
  }

  @Test
  void requestIdentifiersAreCaseSensitive() {
    AdminCreditHandler handler = new AdminCreditHandler(store);
    handler.handle(command("Request-1", "1"));
    handler.handle(command("request-1", "2"));

    assertEquals(Decimal4.of("3"), store.loadBalance("wallet-1").availablePoints4());
    assertEquals(2, recordCount());
  }

  @Test
  void unknownWalletDoesNotCreateBalanceOrRecord() {
    assertThrows(IllegalArgumentException.class, () -> new AdminCreditHandler(store).handle(
        new AdminCreditCommand("missing", "unknown", "key-1", Decimal4.of("1"), "admin-1",
            Instant.EPOCH, "manual credit")));
    assertFalse(store.findByRequestId("missing").isPresent());
    assertEquals(0, recordCount());
  }

  private Callable<Boolean> attempt(
      CountDownLatch start, AdminCreditCommand command, WalletBalance initial) {
    return () -> {
      if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("writers did not start");
      try {
        BillingTestStores.admin(dataSource)
            .saveAtomically(record(command), initial.credit(command.points4()));
        return true;
      } catch (OptimisticLockingFailureException expected) {
        return false;
      }
    };
  }

  private int recordCount() {
    return sql.queryForObject("SELECT COUNT(*) FROM admin_credit_record", Integer.class);
  }

  private static AdminCreditCommand command(String requestId, String points4) {
    return new AdminCreditCommand(requestId, "wallet-1", "key-1", Decimal4.of(points4), "admin-1",
        Instant.parse("2026-09-07T07:00:00.123456789Z"), "人工补偿");
  }

  private static AdminCreditRecord record(AdminCreditCommand command) {
    return new AdminCreditRecord(command.requestId(), command.walletId(), command.keyId(),
        command.points4(), command.adminId(), command.at(), command.remark());
  }
}
