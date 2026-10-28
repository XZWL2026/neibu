package com.xzwl.cloud.media.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.media.application.provider.MpsConfigurationStore;
import com.xzwl.cloud.media.application.provider.MpsConfigurationStore.Configuration;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcMpsConfigurationStoreTest {
  @Test void persistsPoliciesAndRetainsThemForLegacyWriters() {
    var ds=new DriverManagerDataSource("jdbc:h2:mem:mps-policy-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
    Flyway.configure().dataSource(ds).locations("classpath:db/media-migration/common").load().migrate();
    var jdbc=new JdbcTemplate(ds);
    var fallback=new Configuration(0,"id","key","ap-beijing","bucket-123","ap-guangzhou","enhance/",11,12,13);
    var store=new JdbcMpsConfigurationStore(jdbc,fallback);
    var profile=new MpsConfigurationStore.BitrateProfile("br","delivery",12000);
    var template=new MpsConfigurationStore.EnhancementTemplate("fullhd","1080p",java.util.List.of("720p"),true,"br",12L,java.util.Map.of());
    var saved=store.save(new Configuration(0,"id","","ap-beijing","bucket-123","ap-guangzhou","enhance/",11,12,13,java.util.List.of(template),java.util.List.of(profile)));
    assertEquals(java.util.List.of(template),new JdbcMpsConfigurationStore(jdbc,fallback).load().enhancementTemplates());
    var legacy=store.save(new Configuration(1,"id","","ap-beijing","bucket-123","ap-guangzhou","enhance/",21,22,23));
    assertEquals(saved.enhancementTemplates(),legacy.enhancementTemplates()); assertEquals("key",legacy.secretKey());
    assertThrows(IllegalArgumentException.class,()->store.save(new Configuration(2,"id","","ap-beijing","bucket-123","ap-guangzhou","enhance/",21,22,23,java.util.List.of(template),java.util.List.of())));
    assertEquals(2,store.load().version());
    assertThrows(IllegalArgumentException.class,()->MpsConfigurationStore.validatePolicy(java.util.List.of(template,template),java.util.List.of(profile)));
    assertThrows(IllegalArgumentException.class,()->MpsConfigurationStore.validatePolicy(java.util.List.of(new MpsConfigurationStore.EnhancementTemplate("wrong","1080p",java.util.List.of("480p"),true,"br",12L,java.util.Map.of())),java.util.List.of(profile)));
    var empty=store.save(new Configuration(2,"id","","ap-beijing","bucket-123","ap-guangzhou","enhance/",0,0,0,java.util.List.of(),java.util.List.of()));
    assertTrue(empty.enhancementTemplates().isEmpty());assertTrue(empty.bitrateProfiles().isEmpty());
  }

  @Test void migratesExistingThreeTemplateRowWithoutChangingItsMeaning() {
    var ds=new DriverManagerDataSource("jdbc:h2:mem:mps-migration-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
    Flyway.configure().dataSource(ds).locations("classpath:db/media-migration/common").target("17").load().migrate();
    var jdbc=new JdbcTemplate(ds);
    jdbc.update("INSERT INTO media_mps_configuration VALUES(1,4,'id','key','ap-beijing','bucket-123','ap-guangzhou','enhance/',11,12,13)");
    Flyway.configure().dataSource(ds).locations("classpath:db/media-migration/common").load().migrate();
    var loaded=new JdbcMpsConfigurationStore(jdbc,null).load();
    assertEquals(4,loaded.version());assertEquals(11,loaded.template720p());assertEquals(12,loaded.template1080p());assertEquals(13,loaded.template4k());
    assertEquals("key",loaded.secretKey());assertNull(loaded.enhancementTemplates());assertNull(loaded.bitrateProfiles());
  }
  @Test void savesRestoresAndRejectsStaleOrInvalidWritesWithoutLosingSecret() {
    var ds=new DriverManagerDataSource("jdbc:h2:mem:mps-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
    Flyway.configure().dataSource(ds).locations("classpath:db/media-migration/common").load().migrate();
    var jdbc=new JdbcTemplate(ds);
    var fallback=new Configuration(0,"id","key","ap-beijing","bucket-123","ap-guangzhou","enhance/",11,12,13);
    var store=new JdbcMpsConfigurationStore(jdbc,fallback);
    var saved=store.save(new Configuration(0,"id","","ap-beijing","bucket-123","ap-guangzhou","enhance/",21,22,23));
    assertEquals("key",saved.secretKey());assertEquals(1,saved.version());
    assertEquals(saved,new JdbcMpsConfigurationStore(jdbc,fallback).load());
    assertThrows(MpsConfigurationStore.VersionConflict.class,()->store.save(fallback));
    assertThrows(IllegalArgumentException.class,()->store.save(new Configuration(1,"changed","","ap-beijing","bucket-123","ap-guangzhou","enhance/",21,22,23)));
    assertThrows(IllegalArgumentException.class,()->store.save(new Configuration(1,"id","","ap-beijing","bucket-123","ap-guangzhou","../",21,22,23)));
    assertThrows(IllegalArgumentException.class,()->store.save(new Configuration(1,"id","","ap-beijing","bucket-123","ap-guangzhou","enhance/",0,22,23)));
    assertEquals(saved,store.load());
    assertFalse(saved.toString().contains("key"));
  }
}
