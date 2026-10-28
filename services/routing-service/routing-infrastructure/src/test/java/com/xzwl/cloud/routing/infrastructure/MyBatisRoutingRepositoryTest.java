package com.xzwl.cloud.routing.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.xzwl.cloud.routing.application.route.RouteTaskCommand;
import com.xzwl.cloud.routing.application.route.RouteTaskHandler;
import com.xzwl.cloud.routing.domain.model.ModelConfiguration;
import com.xzwl.cloud.routing.domain.provider.Provider;
import com.xzwl.cloud.routing.domain.route.RouteSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.springframework.core.io.ClassPathResource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class MyBatisRoutingRepositoryTest {
  @TempDir Path directory;
  private DriverManagerDataSource dataSource;
  private JdbcTemplate mysqlAdmin;
  private String schema;

  @BeforeEach
  void migrate() {
    String mysqlUrl = System.getenv("ROUTING_TEST_MYSQL_URL");
    if (mysqlUrl == null || mysqlUrl.isBlank()) {
      dataSource = new DriverManagerDataSource(
          "jdbc:h2:file:" + directory.resolve("routing") + ";MODE=MySQL", "sa", "");
    } else {
      if (!mysqlUrl.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/")) {
        throw new IllegalArgumentException("ROUTING_TEST_MYSQL_URL must address local MySQL without a schema");
      }
      String username = System.getenv().getOrDefault("ROUTING_TEST_MYSQL_USERNAME", "root");
      String password = System.getenv().getOrDefault("ROUTING_TEST_MYSQL_PASSWORD", "");
      mysqlAdmin = new JdbcTemplate(new DriverManagerDataSource(mysqlUrl, username, password));
      schema = "routing_mp_" + UUID.randomUUID().toString().replace("-", "");
      mysqlAdmin.execute("CREATE DATABASE " + schema);
      dataSource = new DriverManagerDataSource(mysqlUrl + schema, username, password);
    }
    new ResourceDatabasePopulator(
        new ClassPathResource("db/migration/V1__routing_configuration.sql"),
        new ClassPathResource("db/migration/V2__model_enabled.sql"))
        .execute(dataSource);
  }

  @AfterEach
  void dropTestSchema() {
    if (mysqlAdmin != null && schema != null) mysqlAdmin.execute("DROP DATABASE " + schema);
  }

  private MyBatisRoutingRepository repository() throws Exception {
    var configuration = new MybatisConfiguration();
    configuration.addMapper(RoutingProviderMapper.class);
    configuration.addMapper(RoutingModelMapper.class);
    configuration.addMapper(RouteSnapshotMapper.class);
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(new DriverManagerDataSource(
        dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword()));
    factory.setConfiguration(configuration);
    var session = new SqlSessionTemplate(factory.getObject());
    return new MyBatisRoutingRepository(session.getMapper(RoutingProviderMapper.class),
        session.getMapper(RoutingModelMapper.class), session.getMapper(RouteSnapshotMapper.class));
  }

  @Test void enabledFlagSurvivesRestartAndOldDatabaseRowsDefaultToEnabled() throws Exception {
    var first=repository();
    first.save(Provider.configure("provider","Provider","https://provider.example",true,"{}"));
    new JdbcTemplate(dataSource).update("INSERT INTO routing_model_config(model_id,provider_id,provider_model,configuration_json) VALUES('legacy','provider','upstream','{}')");
    assertTrue(repository().findByModelId("legacy").orElseThrow().enabled());
    first.save(new ModelConfiguration("legacy","provider","upstream","{}",false));
    assertFalse(repository().findByModelId("legacy").orElseThrow().enabled());
    first.save(new ModelConfiguration("legacy","provider","upstream","{}",true));
    assertTrue(repository().findByModelId("legacy").orElseThrow().enabled());
  }

  @Test
  void persistsProviderModelAndTaskSnapshotAcrossRepositoryInstances() throws Exception {
    var first = repository();
    Provider provider = Provider.configure(
        "volcano", "火山引擎", "https://ark.example", true, "{\"timeoutSeconds\":120}");
    ModelConfiguration model = new ModelConfiguration(
        "gpt-image-2", "volcano", "doubao-seedream", "{\"quality\":\"hd\"}");
    RouteSnapshot snapshot = new RouteSnapshot(
        "task-1", "gpt-image-2", "volcano", "火山引擎", "https://ark.example",
        "doubao-seedream", "{\"timeoutSeconds\":120}", "{\"quality\":\"hd\"}",
        Instant.parse("2026-09-07T08:00:00.123456Z"));

    assertTrue(first.findById("missing").isEmpty());
    assertTrue(first.findByModelId("missing").isEmpty());
    assertTrue(first.findByTaskId("missing").isEmpty());

    first.save(provider);
    first.save(model);
    assertTrue(first.saveIfAbsent(snapshot));
    assertFalse(first.saveIfAbsent(new RouteSnapshot(
        "task-1", "other-model", "volcano", "其他显示名", "https://other.example",
        "other-provider-model", "{}", "{}", Instant.parse("2026-09-07T09:00:00Z"))));

    var reopened = repository();
    assertEquals(provider.id(), reopened.findById(provider.id()).orElseThrow().id());
    assertEquals(provider.configurationJson(),
        reopened.findById(provider.id()).orElseThrow().configurationJson());
    assertEquals(model, reopened.findByModelId(model.modelId()).orElseThrow());
    assertEquals(snapshot, reopened.findByTaskId(snapshot.taskId()).orElseThrow());

    reopened.save(Provider.configure(
        "volcano", "changed", "https://changed.example", false, "{}"));
    Provider updatedProvider = reopened.findById("volcano").orElseThrow();
    assertEquals("changed", updatedProvider.displayName());
    assertEquals("https://changed.example", updatedProvider.endpoint());
    assertFalse(updatedProvider.enabled());
    assertEquals("{}", updatedProvider.configurationJson());
    reopened.save(Provider.configure("second", "Second", "https://second.example", true, "{}"));
    var updatedModel = new ModelConfiguration("gpt-image-2", "second", "changed-model", "{}");
    reopened.save(updatedModel);
    assertEquals(updatedModel, reopened.findByModelId(model.modelId()).orElseThrow());
    assertEquals(snapshot, new RouteTaskHandler(reopened, reopened, reopened, Clock.systemUTC())
        .handle(new RouteTaskCommand("task-1", "gpt-image-2")));
    assertEquals(1, new JdbcTemplate(dataSource).queryForObject(
        "SELECT COUNT(*) FROM routing_task_snapshot WHERE task_id = 'task-1'", Integer.class));
  }

  @Test void deletionPersistsWithoutRemovingProviderOrTaskHistory() throws Exception {
    var store = repository();
    store.save(Provider.configure("ark", "Ark", "https://ark.example", true, "{}"));
    store.save(new ModelConfiguration("removed", "ark", "upstream", "{}"));
    store.save(new ModelConfiguration("retained", "ark", "other", "{}"));
    // selected_at is TIMESTAMP(6); use a fixture representable at database precision.
    var snapshot = new RouteTaskHandler(store, store, store,
        Clock.fixed(Instant.parse("2026-09-07T08:00:00.123456Z"), java.time.ZoneOffset.UTC))
        .handle(new RouteTaskCommand("existing-task", "removed"));
    store.deleteByModelId("removed");
    var reopened = repository();
    assertTrue(reopened.findByModelId("removed").isEmpty());
    assertEquals(java.util.List.of("retained"), reopened.findAll().stream().map(ModelConfiguration::modelId).toList());
    assertTrue(reopened.findById("ark").isPresent());
    assertEquals(snapshot, reopened.findByTaskId("existing-task").orElseThrow());
    assertEquals(snapshot, new RouteTaskHandler(reopened, reopened, reopened, Clock.systemUTC())
        .handle(new RouteTaskCommand("existing-task", "removed")));
    reopened.deleteByModelId("removed");
  }

  @Test void listsAllModelsInStableModelIdOrder() throws Exception {
    var store = repository();
    store.save(Provider.configure("provider", "Provider", "https://provider.example", true, "{}"));
    store.save(new ModelConfiguration("z-model", "provider", "upstream-z", "{}"));
    store.save(new ModelConfiguration("a-model", "provider", "upstream-a", "{}"));
    assertEquals(java.util.List.of("a-model", "z-model"),
        repository().findAll().stream().map(ModelConfiguration::modelId).toList());
  }

  @Test
  void concurrentSnapshotInsertsKeepExactlyOneUnchangedWinner() throws Exception {
    var first = repository();
    var second = repository();
    var firstSnapshot = new RouteSnapshot("race", "model", "provider", "First",
        "https://first.example", "first", "{}", "{}", Instant.parse("2026-09-07T08:00:00Z"));
    var secondSnapshot = new RouteSnapshot("race", "model", "provider", "Second",
        "https://second.example", "second", "{}", "{}", Instant.parse("2026-09-07T09:00:00Z"));
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var a = workers.submit(() -> {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return first.saveIfAbsent(firstSnapshot);
      });
      var b = workers.submit(() -> {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return second.saveIfAbsent(secondSnapshot);
      });
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      boolean firstWon = a.get(10, TimeUnit.SECONDS);
      assertEquals(!firstWon, b.get(10, TimeUnit.SECONDS));
      assertEquals(firstWon ? firstSnapshot : secondSnapshot,
          repository().findByTaskId("race").orElseThrow());
    }
  }
}
