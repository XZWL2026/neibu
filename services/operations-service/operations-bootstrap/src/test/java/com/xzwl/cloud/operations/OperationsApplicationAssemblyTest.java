package com.xzwl.cloud.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.xzwl.cloud.operations.application.readmodel.OperationsQueryService;
import com.xzwl.cloud.operations.application.readmodel.OperationsStore;
import com.xzwl.cloud.operations.infrastructure.MyBatisOperationsStore;
import java.time.Instant;
import java.util.Map;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

class OperationsApplicationAssemblyTest {
  @Test
  void applicationMigratesDatabaseAndWiresReadModelService() {
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("isolatedTest", Map.of(
            "spring.datasource.url", "jdbc:h2:mem:operations-assembly;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "spring.datasource.username", "sa",
            "spring.datasource.password", "",
            "spring.datasource.driver-class-name", "org.h2.Driver",
            "spring.main.banner-mode", "off")));
    try (var context = new SpringApplicationBuilder(TemplateApplication.class)
        .environment(environment).web(WebApplicationType.NONE).run()) {
      org.junit.jupiter.api.Assertions.assertTrue(context.containsBean("browserIamSessionFilter"), "Production IAM browser filter must be loaded");
      assertNotNull(context.getBean(OperationsQueryService.class));
      assertEquals(MyBatisOperationsStore.class, context.getBean(OperationsStore.class).getClass());
      assertEquals(LocalCacheScope.STATEMENT,
          context.getBean(SqlSessionFactory.class).getConfiguration().getLocalCacheScope());
      var service = context.getBean(OperationsQueryService.class);
      assertEquals("assembled", service.project(new OperationsStore.Projection(
          "assembly-event", OperationsStore.RecordType.USER, "assembled", "user-1", "ACTIVE",
          null, Instant.parse("2026-09-01T00:00:00.123456789Z"), "assembled", "{}", 1)).recordId());
      assertEquals(1, service.search(new OperationsStore.SearchFilter(
          null, null, null, null, null, null, null, 0, 10)).total());
      assertEquals(1, context.getBean(JdbcTemplate.class).queryForObject(
          "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = TRUE",
          Integer.class));
    }
  }
}
