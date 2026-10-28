package com.xzwl.cloud.operations.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.xzwl.cloud.operations.application.readmodel.OperationsQueryService;
import com.xzwl.cloud.operations.application.readmodel.OperationsStore;
import com.xzwl.cloud.operations.application.readmodel.OperationsStore.RecordType;
import com.xzwl.cloud.operations.infrastructure.persistence.OperationsRecordMapper;
import com.xzwl.cloud.operations.infrastructure.persistence.ProjectionEventMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class MyBatisOperationsStoreTest {
  private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
  private OperationsQueryService service;
  private DataSourceTransactionManager transactionManager;
  private JdbcTemplate mysqlAdmin;
  private String schema;

  @BeforeEach
  void setUp() throws Exception {
    String mysqlUrl = System.getenv("OPERATIONS_TEST_MYSQL_URL");
    DriverManagerDataSource dataSource;
    if (mysqlUrl == null || mysqlUrl.isBlank()) {
      dataSource = new DriverManagerDataSource(
          "jdbc:h2:mem:operations;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
      new JdbcTemplate(dataSource).execute("DROP ALL OBJECTS");
    } else {
      if (!mysqlUrl.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/")) {
        throw new IllegalArgumentException("OPERATIONS_TEST_MYSQL_URL must address local MySQL without a schema");
      }
      String username = System.getenv().getOrDefault("OPERATIONS_TEST_MYSQL_USERNAME", "root");
      String password = System.getenv().getOrDefault("OPERATIONS_TEST_MYSQL_PASSWORD", "");
      mysqlAdmin = new JdbcTemplate(new DriverManagerDataSource(mysqlUrl, username, password));
      schema = "operations_mp_" + UUID.randomUUID().toString().replace("-", "");
      mysqlAdmin.execute("CREATE DATABASE " + schema);
      dataSource = new DriverManagerDataSource(mysqlUrl + schema, username, password);
    }
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    MybatisConfiguration configuration = new MybatisConfiguration();
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    configuration.addMapper(OperationsRecordMapper.class);
    configuration.addMapper(ProjectionEventMapper.class);
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
    MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(dataSource);
    factory.setConfiguration(configuration);
    factory.setPlugins(interceptor);
    SqlSessionTemplate sessions = new SqlSessionTemplate(factory.getObject());
    transactionManager = new DataSourceTransactionManager(dataSource);
    service = new OperationsQueryService(new MyBatisOperationsStore(
        sessions.getMapper(OperationsRecordMapper.class), sessions.getMapper(ProjectionEventMapper.class),
        transactionManager));
  }

  @AfterEach
  void dropTestSchema() {
    if (mysqlAdmin != null && schema != null) mysqlAdmin.execute("DROP DATABASE " + schema);
  }

  @Test
  void projectsAllOperationalRecordTypesAndReplaysIdempotently() {
    int index = 0;
    for (RecordType type : RecordType.values()) {
      var projection = new OperationsStore.Projection(
          "event-" + type, type, "record-" + type, "user-" + (++index), "ACTIVE",
          new BigDecimal(index + ".2500"), T0.plusSeconds(index), type + " summary",
          "{\"kind\":\"" + type + "\"}", 1);
      assertEquals(projection.recordId(), service.project(projection).recordId());
      assertEquals(projection.recordId(), service.project(projection).recordId());
    }
    assertEquals(6, service.search(new OperationsStore.SearchFilter(
        null, null, null, null, null, null, null, 0, 20)).total());
  }

  @Test
  void conflictingProjectionRollsBackAndFiltersAreApplied() {
    var original = new OperationsStore.Projection(
        "event-1", RecordType.RECHARGE, "recharge-1", "user-1", "PAID",
        new BigDecimal("100.0000"), T0, "paid recharge", "{\"channel\":\"WECHAT\"}", 1);
    service.project(original);
    assertThrows(IllegalStateException.class, () -> service.project(
        new OperationsStore.Projection("event-1", RecordType.RECHARGE, "recharge-1", "user-1",
            "REFUNDED", new BigDecimal("100.0000"), T0, "changed", "{}", 1)));
    assertEquals("PAID", service.get(RecordType.RECHARGE, "recharge-1").status());

    var page = service.search(new OperationsStore.SearchFilter(
        RecordType.RECHARGE, "user-1", "PAID", T0.minusSeconds(1), T0.plusSeconds(1),
        new BigDecimal("99"), new BigDecimal("101"), 0, 10));
    assertEquals(1, page.total());
    assertEquals("recharge-1", page.items().getFirst().recordId());
  }

  @Test
  void newerSourceVersionUpdatesRecordButOlderVersionCannotOverwriteIt() {
    service.project(new OperationsStore.Projection(
        "event-1", RecordType.VIDEO, "video-1", "user-1", "PROCESSING", null, T0,
        "processing", "{}", 1));
    service.project(new OperationsStore.Projection(
        "event-2", RecordType.VIDEO, "video-1", "user-1", "READY", null, T0.plusSeconds(1),
        "ready", "{\"duration\":10}", 2));
    assertEquals("READY", service.get(RecordType.VIDEO, "video-1").status());
    assertThrows(IllegalStateException.class, () -> service.project(new OperationsStore.Projection(
        "event-3", RecordType.VIDEO, "video-1", "user-1", "PROCESSING", null,
        T0.plusSeconds(2), "late", "{}", 1)));
  }

  @Test
  void timeFiltersPreserveChronologicalOrderAcrossFractionalSeconds() {
    service.project(new OperationsStore.Projection(
        "event-1", RecordType.IMAGE, "image-1", "user-1", "READY", null, T0,
        "whole second", "{}", 1));
    service.project(new OperationsStore.Projection(
        "event-2", RecordType.IMAGE, "image-2", "user-1", "READY", null,
        T0.plusMillis(500), "half second", "{}", 1));

    var page = service.search(new OperationsStore.SearchFilter(
        RecordType.IMAGE, null, null, T0.plusMillis(250), null, null, null, 0, 10));
    assertEquals(1, page.total());
    assertEquals("image-2", page.items().getFirst().recordId());
  }

  @Test
  void compositeKeysNullOverwriteAndNanosecondTimestampsSurviveProjection() {
    Instant precise = T0.plusNanos(123456789);
    service.project(new OperationsStore.Projection(
        "event-image", RecordType.IMAGE, "shared", "user-1", "READY", new BigDecimal("12.34567"),
        precise, "image", "{}", 1));
    service.project(new OperationsStore.Projection(
        "event-video", RecordType.VIDEO, "shared", "user-1", "READY", new BigDecimal("98.7654"),
        precise, "video", "{}", 1));
    assertEquals(new BigDecimal("12.3456"), service.get(RecordType.IMAGE, "shared").amount());
    var updated = service.project(new OperationsStore.Projection(
        "event-clear", RecordType.IMAGE, "shared", "user-1", "READY", null,
        precise, "image", "{}", 2));
    assertNull(updated.amount());
    assertEquals(precise, updated.occurredAt());
    assertEquals(new BigDecimal("98.7654"), service.get(RecordType.VIDEO, "shared").amount());
    var replayed = service.project(new OperationsStore.Projection(
        "event-same-version", RecordType.IMAGE, "shared", "user-1", "READY", null,
        precise, "image", "{}", 2));
    assertEquals(updated.projectedAt(), replayed.projectedAt());
    assertThrows(IllegalStateException.class, () -> service.project(new OperationsStore.Projection(
        "event-conflict", RecordType.IMAGE, "shared", "user-1", "READY", null,
        precise, "changed", "{}", 2)));
    service.project(new OperationsStore.Projection(
        "event-conflict", RecordType.IMAGE, "shared", "user-1", "READY", null,
        precise, "image", "{}", 2));
  }

  @Test
  void paginationUsesChronologicalThenRecordIdOrderAndRetainsTotalPastLastPage() {
    for (String id : List.of("z", "a", "new")) {
      service.project(new OperationsStore.Projection(
          "event-" + id, RecordType.IMAGE, id, "user-1", "READY", null,
          id.equals("new") ? T0.plusNanos(1) : T0, id, "{}", 1));
    }
    var first = service.search(new OperationsStore.SearchFilter(
        RecordType.IMAGE, " ", "", null, null, null, null, 0, 2));
    assertEquals(List.of("new", "a"), first.items().stream().map(OperationsStore.OperationalRecord::recordId).toList());
    assertEquals(3, first.total());
    var second = service.search(new OperationsStore.SearchFilter(
        RecordType.IMAGE, null, null, null, null, null, null, 1, 2));
    assertEquals(List.of("z"), second.items().stream().map(OperationsStore.OperationalRecord::recordId).toList());
    var empty = service.search(new OperationsStore.SearchFilter(
        RecordType.IMAGE, null, null, null, null, null, null, 2, 2));
    assertEquals(List.of(), empty.items());
    assertEquals(3, empty.total());
  }

  @Test
  void projectionAndEventParticipateInTheSurroundingSpringTransaction() {
    var projection = new OperationsStore.Projection(
        "event-rollback", RecordType.USER, "rollback", "user-1", "ACTIVE", null, T0,
        "user", "{}", 1);
    assertThrows(IllegalStateException.class, () ->
        new TransactionTemplate(transactionManager).execute(status -> {
          service.project(projection);
          throw new IllegalStateException("rollback outer transaction");
        }));
    assertThrows(IllegalArgumentException.class, () -> service.get(RecordType.USER, "rollback"));
    assertEquals("rollback", service.project(projection).recordId());
  }

  @Test
  void concurrentFirstProjectionsWithDistinctEventsPreserveOneRecord() throws Exception {
    var ready = new CountDownLatch(8);
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(8)) {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<OperationsStore.OperationalRecord>>();
      for (int i=0; i<8; i++) {
        String event = "first-"+i;
        futures.add(pool.submit(() -> {
          ready.countDown();
          if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("start timeout");
          return service.project(new OperationsStore.Projection(event, RecordType.USER, "new-user", "new-user",
              "ACTIVE", null, T0, "user", "{}", 1));
        }));
      }
      org.junit.jupiter.api.Assertions.assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
      start.countDown();
      for (var future : futures) assertEquals("new-user", future.get(15, java.util.concurrent.TimeUnit.SECONDS).recordId());
    }
    assertEquals(1, service.search(new OperationsStore.SearchFilter(
        RecordType.USER, null, null, null, null, null, null, 0, 10)).total());
  }

  @Test
  void concurrentProjectionReplayProducesOneRecord() throws Exception {
    var projection = new OperationsStore.Projection(
        "event-1", RecordType.USER, "user-1", "user-1", "ACTIVE", null, T0,
        "user", "{}", 1);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    Callable<OperationsStore.OperationalRecord> request = () -> {
      ready.countDown();
      start.await();
      return service.project(projection);
    };
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(request);
      var second = pool.submit(request);
      ready.await();
      start.countDown();
      assertEquals(first.get().recordId(), second.get().recordId());
    }
    assertEquals(1, service.search(new OperationsStore.SearchFilter(
        RecordType.USER, null, null, null, null, null, null, 0, 10)).total());
  }
}
