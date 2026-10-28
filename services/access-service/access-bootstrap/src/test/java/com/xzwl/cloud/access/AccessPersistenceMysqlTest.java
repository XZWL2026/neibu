package com.xzwl.cloud.access;
import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.access.application.key.*;
import com.xzwl.cloud.access.domain.key.AccountType;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
@EnabledIfEnvironmentVariable(named="ACCESS_TEST_MYSQL_URL",matches=".+")
class AccessPersistenceMysqlTest {
  @Test void onePrimaryAfterPaymentSurvivesRestartAndDisablingIsImmediate() throws Exception {
    String root=System.getenv("ACCESS_TEST_MYSQL_URL");
    if(!root.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"))throw new IllegalArgumentException("local MySQL required");
    String schema="abp_access_"+UUID.randomUUID().toString().replace("-","");
    String databaseUser=System.getenv().getOrDefault("ACCESS_TEST_MYSQL_USERNAME","root");
    String databasePassword=System.getenv().getOrDefault("ACCESS_TEST_MYSQL_PASSWORD","");
    var sql=new JdbcTemplate(new DriverManagerDataSource(root,databaseUser,databasePassword));
    sql.execute("CREATE DATABASE "+schema+" COLLATE utf8mb4_0900_bin");
    var settings=Map.<String,Object>of("spring.datasource.url",root+schema,"spring.datasource.username",databaseUser,
        "spring.datasource.password",databasePassword,"abp.internal-token","test-only-service-token-0000000000000000",
        "access.key-encryption-key","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "access.user-internal-token","test-only-user-token-0000000000000000000");
    String secret;
    try{
      try(var app=start(settings)){
        var store=app.getBean(AccessKeyStore.class);
        assertTrue(store.findByOwner("user").isEmpty());

        try(var pool=Executors.newFixedThreadPool(8)){
          var gate=new CountDownLatch(1);var jobs=new ArrayList<Future<AccessKey>>();
          for(int i=0;i<8;i++) jobs.add(pool.submit(()->{gate.await();return store.ensurePrimary("user",AccountType.API,"paid-1");}));
          gate.countDown();var first=jobs.getFirst().get(20,TimeUnit.SECONDS);
          for(var job:jobs)assertEquals(first,job.get(20,TimeUnit.SECONDS));
          secret=first.secret();
        }
        var keys=new AccessKeys(store,id->true);
        assertEquals("user",keys.authorize(secret,"API").ownerId());
        assertThrows(IllegalStateException.class,()->store.ensurePrimary("user",AccountType.PERSONAL,"paid-2"));
        String keyId=store.findByOwner("user").orElseThrow().id();
        store.setEnabled(keyId,false);
        assertThrows(AccessKeys.Denied.class,()->keys.authorize(secret,"API"));
        store.setEnabled(keyId,true);
        assertThrows(AccessKeys.Denied.class,()->new AccessKeys(store,id->false).authorize(secret,"API"));
        assertThrows(AccessKeys.Denied.class,()->keys.authorize(secret,"MEDIA"));
        var db=new JdbcTemplate(app.getBean(javax.sql.DataSource.class));
        assertFalse(db.queryForObject("SELECT encrypted_secret FROM access_key",String.class).contains(secret));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM access_key",Integer.class));
      }
      try(var app=start(settings)){
        assertEquals(secret,app.getBean(AccessKeyStore.class).findByOwner("user").orElseThrow().secret());
        assertEquals("paid-1",app.getBean(AccessKeyStore.class).findByOwner("user").orElseThrow().firstRechargeOrderId());
      }
    }finally{sql.execute("DROP DATABASE "+schema);}
  }
  @Test void adminKeyIsOptionalAndSecureAndFirstPaymentAndTypeChangesPreserveIt()throws Exception{
    String root=System.getenv("ACCESS_TEST_MYSQL_URL"),schema="admin_access_"+UUID.randomUUID().toString().replace("-","");
    assertTrue(root.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"));
    String user=System.getenv().getOrDefault("ACCESS_TEST_MYSQL_USERNAME","root"),password=System.getenv().getOrDefault("ACCESS_TEST_MYSQL_PASSWORD","");
    var sql=new JdbcTemplate(new DriverManagerDataSource(root,user,password));sql.execute("CREATE DATABASE "+schema+" COLLATE utf8mb4_0900_bin");
    try(var app=start(Map.of("spring.datasource.url",root+schema,"spring.datasource.username",user,"spring.datasource.password",password,
      "abp.internal-token","test-only-service-token-0000000000000000","access.key-encryption-key","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","access.user-internal-token","test-only-user-token-0000000000000000000"))){
      var store=app.getBean(AccessKeyStore.class);
      store.manage("no-key","managed",AccountType.PERSONAL,false,"admin");assertTrue(store.findByOwner("managed").isEmpty());
      try(var pool=Executors.newFixedThreadPool(4)){
        var tasks=new ArrayList<Future<?>>();for(int i=0;i<4;i++)tasks.add(pool.submit(()->store.manage("create","managed",AccountType.PERSONAL,true,"admin")));for(var job:tasks)job.get();
      }
      var key=store.findByOwner("managed").orElseThrow();assertNull(key.firstRechargeOrderId());assertTrue(key.secret().matches("sk-_[0-9a-f]{64}"));
      assertEquals(1,store.list("managed").size());
      assertThrows(IllegalStateException.class,()->store.manage("create","managed",AccountType.ENTERPRISE,true,"admin"));
      store.setEnabled(key.id(),false);store.manage("enterprise","managed",AccountType.ENTERPRISE,true,"admin");
      var changed=store.findByOwner("managed").orElseThrow();assertEquals(key.id(),changed.id());assertEquals(key.secret(),changed.secret());assertFalse(changed.enabled());assertEquals(AccountType.ENTERPRISE,changed.accountType());
      var paid=store.ensurePrimary("managed",AccountType.ENTERPRISE,"real-paid");assertEquals(key.id(),paid.id());assertEquals("real-paid",paid.firstRechargeOrderId());assertFalse(paid.enabled());
      store.manage("back","managed",AccountType.PERSONAL,false,"admin");assertEquals("real-paid",store.findByOwner("managed").orElseThrow().firstRechargeOrderId());
      store.manage("enterprise","managed",AccountType.ENTERPRISE,true,"admin");assertEquals(AccountType.PERSONAL,store.findByOwner("managed").orElseThrow().accountType(),"old replay never rolls current identity back");
      assertEquals(AccountType.PERSONAL,store.ensurePrimary("managed",AccountType.ENTERPRISE,"real-paid",java.time.Instant.parse("2026-09-18T00:00:00Z")).accountType(),"upgrade replay accepts previously applied legacy request without changing identity");
      var personal=store.ensurePrimary("managed",AccountType.PERSONAL,"switch-personal",java.time.Instant.parse("2026-09-18T00:00:01Z"));
      var enterprise=store.ensurePrimary("managed",AccountType.ENTERPRISE,"switch-enterprise",java.time.Instant.parse("2026-09-18T00:00:02Z"));
      assertEquals(personal.id(),enterprise.id());assertEquals(personal.secret(),enterprise.secret());assertFalse(enterprise.enabled());
      assertEquals(AccountType.ENTERPRISE,enterprise.accountType());assertEquals("real-paid",enterprise.firstRechargeOrderId());
      assertEquals(AccountType.ENTERPRISE,store.ensurePrimary("managed",AccountType.PERSONAL,"switch-personal",java.time.Instant.parse("2026-09-18T00:00:01Z")).accountType(),"paid-order replay cannot roll identity back");
      assertThrows(IllegalStateException.class,()->store.ensurePrimary("managed",AccountType.PERSONAL,"switch-enterprise"));
      assertThrows(IllegalStateException.class,()->store.ensurePrimary("managed",AccountType.API,"api-conversion"));
      var beforeApi=store.findByOwner("managed").orElseThrow();
      store.manage("convert-api","managed",AccountType.API,true,"admin");
      var apiKey=store.findByOwner("managed").orElseThrow();
      assertEquals(beforeApi.id(),apiKey.id());assertEquals(beforeApi.secret(),apiKey.secret());
      assertEquals(beforeApi.enabled(),apiKey.enabled());assertEquals(beforeApi.firstRechargeOrderId(),apiKey.firstRechargeOrderId());
      assertEquals(AccountType.API,apiKey.accountType());assertEquals("API",apiKey.kind());
      store.manage("convert-api","managed",AccountType.API,true,"admin");assertEquals(1,store.list("managed").size());
      store.setEnabled(apiKey.id(),true);var auth=new AccessKeys(store,id->true);
      assertEquals(apiKey.id(),auth.authorize(apiKey.secret(),"API").id());
      assertThrows(AccessKeys.Denied.class,()->auth.authorize(apiKey.secret(),"MEDIA"));
      store.setEnabled(apiKey.id(),false);
      store.manage("api-back","managed",AccountType.PERSONAL,false,"admin");
      var restored=store.findByOwner("managed").orElseThrow();
      assertEquals(apiKey.id(),restored.id());assertEquals(apiKey.secret(),restored.secret());assertFalse(restored.enabled());
      assertEquals(apiKey.firstRechargeOrderId(),restored.firstRechargeOrderId());assertEquals(AccountType.PERSONAL,restored.accountType());assertEquals("MEDIA",restored.kind());
      store.manage("convert-api","managed",AccountType.API,true,"admin");assertEquals(AccountType.PERSONAL,store.findByOwner("managed").orElseThrow().accountType());
      store.setEnabled(apiKey.id(),true);assertEquals(apiKey.id(),auth.authorize(apiKey.secret(),"MEDIA").id());
      assertThrows(AccessKeys.Denied.class,()->auth.authorize(apiKey.secret(),"API"));
      var originalApi=store.ensurePrimary("api",AccountType.API,"api-paid");
      store.manage("api-same","api",AccountType.API,true,"admin");
      store.manage("api-change","api",AccountType.ENTERPRISE,false,"admin");
      var converted=store.findByOwner("api").orElseThrow();
      assertEquals(originalApi.id(),converted.id());assertEquals(originalApi.secret(),converted.secret());assertEquals("api-paid",converted.firstRechargeOrderId());
      assertEquals(AccountType.ENTERPRISE,converted.accountType());assertEquals("MEDIA",converted.kind());
      store.manage("api-change","api",AccountType.ENTERPRISE,false,"admin");assertEquals(1,store.list("api").size());
      assertEquals(AccountType.ENTERPRISE,store.ensurePrimary("api",AccountType.API,"api-paid").accountType());
      store.setEnabled(key.id(),true);var keys=new AccessKeys(store,id->false);assertThrows(AccessKeys.Denied.class,()->keys.authorize(key.secret(),"MEDIA"));assertFalse(keys.keyStatus(key.id()).enabled());
      assertEquals(key.secret(),store.findById(key.id()).orElseThrow().secret());
    }finally{sql.execute("DROP DATABASE "+schema);}
  }
  private static org.springframework.context.ConfigurableApplicationContext start(Map<String,Object> props){
    var env=new StandardEnvironment();env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    env.getPropertySources().addFirst(new MapPropertySource("test",props));
    return new SpringApplicationBuilder(TemplateApplication.class).environment(env).web(WebApplicationType.NONE)
      .run("--spring.config.location=classpath:/application.yml");
  }
}
