package com.xzwl.cloud.promotion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.xzwl.cloud.promotion.application.production.PromotionService;
import com.xzwl.cloud.promotion.application.production.PromotionStore;
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

class PromotionApplicationAssemblyTest {
  @Test
  void applicationMigratesDatabaseAndWiresProductionService() {
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("isolatedTest", Map.of(
            "spring.datasource.url", "jdbc:h2:mem:promotion-assembly;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "spring.datasource.username", "sa",
            "spring.datasource.password", "",
            "spring.datasource.driver-class-name", "org.h2.Driver",
            "spring.main.banner-mode", "off")));
    try (var context = new SpringApplicationBuilder(TemplateApplication.class)
        .environment(environment).web(WebApplicationType.NONE).run()) {
      org.junit.jupiter.api.Assertions.assertTrue(context.containsBean("browserIamSessionFilter"), "Production IAM browser filter must be loaded");
      assertNotNull(context.getBean(PromotionService.class));
      assertNotNull(context.getBean(PromotionStore.class));
      assertEquals("MyBatisPromotionStore", context.getBean(PromotionStore.class).getClass().getSimpleName());
      assertEquals(LocalCacheScope.STATEMENT,
          context.getBean(SqlSessionFactory.class).getConfiguration().getLocalCacheScope());
      var service = context.getBean(PromotionService.class);
      var at = Instant.parse("2026-09-01T00:00:00.123456789Z");
      ((com.xzwl.cloud.promotion.infrastructure.MyBatisPromotionStore)context.getBean(PromotionStore.class)).setLiveApplicants(owner -> new PromotionStore.LiveApplicant(owner,"PERSONAL",true,at,1,"测试用户","138****0000"));
      service.projectApplicantEligibility(new PromotionStore.ProjectApplicantEligibility(
          "assembly-eligibility", "assembly-user", "PERSONAL", "ENABLED", at, 1, at));
      service.submitApplication(new PromotionStore.SubmitApplication(
          "assembly-submit", "assembly-application", "assembly-user", "share", "community", true, at));
      assertEquals(1, service.listApplications("PENDING", "assembly-user", 0, 1).total());
      assertEquals(0, service.listApplications("PENDING", "assembly-user", 1, 1).items().size());
      var db=context.getBean(JdbcTemplate.class);
      db.update("INSERT INTO referral_binding(referred_user_id,version,command_id,direct_inviter_user_id,cash_tier,effective_at) VALUES('invited',1,'legacy-user-binding','inviter','NONE',?)",at.toString());
      db.update("INSERT INTO referral_binding(referred_user_id,version,command_id,direct_inviter_user_id,cash_tier,effective_at) VALUES('invited',2,'admin-rebind','other','NONE',?)",at.plusSeconds(1).toString());
      db.update("INSERT INTO referral_binding(referred_user_id,version,command_id,direct_inviter_user_id,cash_tier,effective_at) VALUES('natural',1,'admin-initial','inviter','NONE',?)",at.toString());
      for(String command:java.util.List.of("admin-initial","admin-rebind"))db.update("INSERT INTO promotion_admin_operation(command_id,kind,subject_id,admin_id,facts,occurred_at) VALUES(?,'REFERRAL',?,'admin','{}',?)",command,command.equals("admin-initial")?"natural":"invited",at.toString());
      assertEquals(java.util.List.of("invited"),service.invitationSources(java.util.List.of("invited","natural","missing")));
      org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->service.invitationSources(java.util.Collections.nCopies(101,"owner")));
      assertEquals(1, context.getBean(JdbcTemplate.class).queryForObject(
          "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = TRUE",
          Integer.class));
    }
  }
}
