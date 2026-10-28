package com.xzwl.cloud.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.media.application.provider.ArkConnectionStore;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.task.*;
import com.xzwl.cloud.media.infrastructure.provider.*;
import java.time.Clock;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetConfigurationTest {
  JdbcTemplate jdbc;
  MockEnvironment env;
  @BeforeEach void setup() {
    var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
    Flyway.configure().dataSource(ds).locations("classpath:db/media-migration/common").load().migrate();jdbc=new JdbcTemplate(ds);
    env=new MockEnvironment().withProperty("MEDIA_ASSET_PROVIDER_ID","ark")
        .withProperty("MEDIA_ASSET_PROJECT_NAME","local-project").withProperty("MEDIA_ASSET_ACCESS_KEY_ID","test-ak")
        .withProperty("MEDIA_ASSET_SECRET_ACCESS_KEY","test-sk");
  }
  JdbcPortraitReviews bean() {
    var connections=mock(ArkConnectionStore.class);
    when(connections.load(anyString())).thenAnswer(i->new ArkConnectionStore.Connection(0,i.getArgument(0),"https://ark.cn-beijing.volces.com/api/v3","test-generation-key"));
    var routing=mock(RoutingPort.class);
    when(routing.models()).thenReturn(List.of(new RoutingPort.Model("video","doubao-seedance-2-5-260628",Map.of(),"routed-ark")));
    var storage=mock(StoragePort.class);
    when(storage.resolveReferences(anyString(),anyList(),eq(ReferenceMode.OMNI)))
        .thenReturn(List.of(new MediaReference(null,"https://cdn.example.test/test.png")));
    var beans=new DefaultListableBeanFactory();
    beans.registerSingleton("configuration",new MediaConfiguration());beans.registerSingleton("environment",env);
    beans.registerSingleton("jdbc",jdbc);beans.registerSingleton("json",new ObjectMapper());
    beans.registerSingleton("connections",connections);beans.registerSingleton("routing",routing);
    beans.registerSingleton("storage",storage);beans.registerSingleton("http",mock(ProviderHttp.class));beans.registerSingleton("clock",Clock.systemUTC());
    var definition=new RootBeanDefinition();definition.setFactoryBeanName("configuration");definition.setFactoryMethodName("mediaPortraitReviews");
    definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("reviews",definition);
    return beans.getBean(JdbcPortraitReviews.class);
  }
  String project(JdbcPortraitReviews reviews) {
    var row=reviews.submit("owner",UUID.randomUUID().toString(),"file","video",MediaReference.Kind.IMAGE);
    return jdbc.queryForObject("SELECT project_name FROM media_portrait WHERE id=?",String.class,row.id());
  }
  @Test void bindsRoutedProviderWhileReadingAndFreezingProjectProperties() {
    env.withProperty("media.asset.project-name","nacos-project").withProperty("media.asset.provider-id","nacos-ark");
    var reviews=bean();env.setProperty("media.asset.project-name","later-project");
    assertEquals("nacos-project",project(reviews));
    assertEquals("routed-ark",jdbc.queryForObject("SELECT provider_id FROM media_portrait",String.class));
  }
  @Test void removingMigratedOsVariableLetsNacosPropertyTakeEffect() {
    env.getPropertySources().addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource("os",
        Map.of("MEDIA_ASSET_PROJECT_NAME","os-project")));
    env.withProperty("media.asset.project-name","nacos-project");
    assertEquals("os-project",project(bean()));
    env.getPropertySources().remove("os");
    assertEquals("nacos-project",project(bean()));
  }
  @Test void preservesLocalEnvironmentFallback() { assertEquals("local-project",project(bean())); }
  @Test void providerProjectBindingsAreReadFromEnvironmentOrNacosAndFrozenWithTheBean() {
    env.withProperty("MEDIA_ASSET_PROJECT_NAMES","{\"routed-ark\":\"supplier-project\"}");
    assertEquals("supplier-project",project(bean()));
    env.withProperty("media.asset.project-names","{\"routed-ark\":\"nacos-supplier-project\"}");
    var reviews=bean();env.setProperty("media.asset.project-names","{\"routed-ark\":\"later-project\"}");
    assertEquals("nacos-supplier-project",project(reviews));
  }
  @Test void invalidProjectFailsWithoutPersistingAnAuditRequest() {
    env.withProperty("media.asset.project-name","invalid project");
    assertThrows(RuntimeException.class,()->project(bean()));
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM media_portrait",Integer.class));
  }
}
