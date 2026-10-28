package com.xzwl.cloud.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.xzwl.cloud.billing.application.credit.AdminCreditCommand;
import com.xzwl.cloud.billing.application.credit.AdminCreditHandler;
import com.xzwl.cloud.billing.application.credit.AdminCreditResult;
import com.xzwl.cloud.billing.application.credit.AdminCreditStore;
import com.xzwl.cloud.billing.application.recharge.CreditPaidRechargeCommand;
import com.xzwl.cloud.billing.application.recharge.CreditPaidRechargeHandler;
import com.xzwl.cloud.billing.application.recharge.RechargeCreditResult;
import com.xzwl.cloud.billing.application.recharge.RechargeRateStore;
import com.xzwl.cloud.billing.application.recharge.SetRechargeRateHandler;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.billing.domain.recharge.RechargeRate;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "BILLING_TEST_MYSQL_URL", matches = ".+")
class BillingJdbcApplicationMysqlTest {

  @Test
  void applicationMigratesAndPersistsCreditsAcrossRestarts() {
    String mysqlUrl = System.getenv("BILLING_TEST_MYSQL_URL");
    if (!mysqlUrl.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/")) {
      throw new IllegalArgumentException("BILLING_TEST_MYSQL_URL must address local MySQL without a schema");
    }
    String username = System.getenv().getOrDefault("BILLING_TEST_MYSQL_USERNAME", "root");
    String password = System.getenv("BILLING_TEST_MYSQL_PASSWORD");
    JdbcTemplate mysqlAdmin = new JdbcTemplate(new DriverManagerDataSource(mysqlUrl, username, password));
    String database = "billing_boot_test_" + UUID.randomUUID().toString().replace("-", "");
    mysqlAdmin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
    try {
      Map<String, Object> properties = Map.of(
          "spring.datasource.url", mysqlUrl + database + "?connectionTimeZone=UTC",
          "spring.datasource.username", username,
          "spring.datasource.password", password,
          "spring.main.banner-mode", "off",
          "abp.internal-token", "test-only-service-token-0000000000000000");
      AdminCreditCommand command = new AdminCreditCommand("boot-credit-1", "wallet-1", "key-1",
          Decimal4.of("12.34569"), "admin-1", Instant.parse("2026-09-07T07:00:00Z"), "本地联调");
      AdminCreditResult first;
      CreditPaidRechargeCommand paidCommand = new CreditPaidRechargeCommand(
          "boot-paid-1", "online-user-1", "online-wallet-1", "online-key-1",
          Decimal4.of("10"), Decimal4.of("100"), Instant.parse("2026-09-07T07:00:00Z"));
      RechargeCreditResult paid;
      try (ConfigurableApplicationContext app = start(properties)) {
        assertEquals(new RechargeRate(Decimal4.of("10"), 1),
            app.getBean(RechargeRateStore.class).load());
        app.getBean(SetRechargeRateHandler.class).handle(Decimal4.of("25"), 1);
        AdminCreditHandler handler = app.getBean(AdminCreditHandler.class);
        JdbcTemplate jdbc = new JdbcTemplate(app.getBean(DataSource.class));
        assertEquals(database, jdbc.queryForObject("SELECT DATABASE()", String.class));
        jdbc.update("INSERT INTO wallet (wallet_id, available_points4, version) VALUES (?, ?, ?)",
            "wallet-1", Decimal4.zero().asBigDecimal(), 1);
        first = handler.handle(command);
        assertEquals(Decimal4.of("12.3456"), first.balance().availablePoints4());
        paid = app.getBean(CreditPaidRechargeHandler.class).handle(paidCommand);
        assertEquals(Decimal4.of("1000"), paid.balance().availablePoints4());
        assertEquals(3, jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version IN ('1', '2', '3')",
            Integer.class));
      }
      try (ConfigurableApplicationContext app = start(properties)) {
        assertEquals(new RechargeRate(Decimal4.of("25"), 2),
            app.getBean(RechargeRateStore.class).load());
        AdminCreditStore store = app.getBean(AdminCreditStore.class);
        assertEquals(first.balance(), store.loadBalance("wallet-1"));
        assertEquals(first, app.getBean(AdminCreditHandler.class).handle(command));
        assertEquals(paid.balance(), store.loadBalance("online-wallet-1"));
        assertEquals(paid, app.getBean(CreditPaidRechargeHandler.class).handle(paidCommand));
        JdbcTemplate jdbc = new JdbcTemplate(app.getBean(DataSource.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM admin_credit_record", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM recharge_credit_record", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM funding_lot", Integer.class));
        assertNotNull(store.findByRequestId(command.requestId()).orElseThrow());
      }
    } finally {
      mysqlAdmin.execute("DROP DATABASE " + database);
    }
  }

  private static ConfigurableApplicationContext start(Map<String, Object> properties) {
    // A developer's active profile or datasource/Flyway overrides must never select a user schema.
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment.getPropertySources().addFirst(new MapPropertySource("isolatedMysqlTest", properties));
    return new SpringApplicationBuilder(TemplateApplication.class)
        .environment(environment).web(WebApplicationType.NONE)
        .run("--spring.config.location=classpath:/application.yml");
  }
}
