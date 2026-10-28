package com.xzwl.cloud.iam;

import static org.junit.jupiter.api.Assertions.*;
import static com.xzwl.cloud.iam.ReleaseAuthMysqlTest.*;
import com.xzwl.cloud.iam.application.otp.SmsOtpProvider;
import com.xzwl.cloud.iam.interfaces.http.SessionLogin;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

@EnabledIfEnvironmentVariable(named="IAM_TEST_MYSQL_URL", matches=".+")
class UserSessionMysqlTest {
  @Test void realUserServiceRegistersOnceAndInvalidatesAllDevicesOnDisable() throws Exception {
    try (Database iamDb = new Database(); Database userDb = new Database()) {
      int userPort = freePort();
      Map<String,Object> settings = Map.of("iam.user-service-url", "http://127.0.0.1:" + userPort);
      try (var iam = start(iamDb,"prod",settings,SmsConfiguration.class);
           var user = startUser(userDb,userPort,iam.getWebServer().getPort());
           var peer = start(iamDb,"prod",settings,SmsConfiguration.class)) {
        var clock=iam.getBean(OtpPolicyMysqlTest.MutableClock.class);
        var sms=iam.getBean(RecordingSms.class);
        var first=new Browser(iam);
        assertEquals(401, userRequest(first,user,"GET","/api/v1/users/me",Map.of(),null).statusCode());
        assertEquals(403, first.post("/api/v1/auth/otp/send",Map.of("phone","13800000001"),null).statusCode());
        var sent=first.post("/api/v1/auth/otp/send",Map.of("phone","13800000001"),first.csrf());
        assertEquals(200,sent.statusCode(),sent.body());
        String code=sms.codes.get("+8613800000001");
        assertFalse(sent.body().contains(code));
        assertEquals(0,userDb.jdbc.queryForObject("SELECT COUNT(*) FROM user_identity",Integer.class));
        assertEquals(401,first.post("/api/v1/auth/otp/login",Map.of("phone","13800000001","code","bad"),first.csrf()).statusCode());
        assertEquals(0,userDb.jdbc.queryForObject("SELECT COUNT(*) FROM user_identity",Integer.class));
        String anonymousCookie=first.cookie();
        assertEquals(200,first.post("/api/v1/auth/otp/login",Map.of("phone","13800000001","code",code),first.csrf()).statusCode());
        assertNotEquals(anonymousCookie,first.cookie());
        String userId=first.session().path("user").path("id").asText();
        assertEquals("user",first.session().path("roles").get(0).asText());
        assertEquals(2_592_000,iamDb.jdbc.queryForObject("SELECT MAX(MAX_INACTIVE_INTERVAL) FROM SPRING_SESSION",Integer.class));
        assertEquals(200,userRequest(first,user,"GET","/api/v1/users/me",Map.of(),null).statusCode());
        assertEquals(403,userRequest(first,user,"PATCH","/api/v1/admin/users/"+userId+"/status",Map.of("enabled",false),first.csrf()).statusCode());
        var second=new Browser(iam);
        clock.advance(60_000); loginUser(second,sms,"13800000001");
        assertEquals(userId,second.session().path("user").path("id").asText());
        assertEquals(1,userDb.jdbc.queryForObject("SELECT COUNT(*) FROM user_identity",Integer.class));
        assertNotEquals(first.cookie(),second.cookie());
        // The same persisted session is valid on another IAM instance.
        second.base="http://127.0.0.1:"+peer.getWebServer().getPort();
        assertTrue(second.session().path("authenticated").asBoolean());
        assertEquals(200,first.post("/api/v1/auth/logout",Map.of(),first.csrf()).statusCode());
        assertFalse(first.session().path("authenticated").asBoolean());
        assertTrue(second.session().path("authenticated").asBoolean());
        clock.advance(60_000); loginUser(first,sms,"13800000001");
        var admin=new Browser(iam);
        assertEquals(200,admin.post("/api/v1/auth/admin/login",credentials(),admin.csrf()).statusCode());
        assertEquals(403,userRequest(admin,user,"PATCH","/api/v1/admin/users/"+userId+"/status",Map.of("enabled",false),null).statusCode());
        assertEquals(200,userRequest(admin,user,"PATCH","/api/v1/admin/users/"+userId+"/status",Map.of("enabled",false),admin.csrf()).statusCode());
        assertFalse(first.session().path("authenticated").asBoolean());
        // Leave the peer's old session untouched during disable; its version must still fail after re-enable.
        assertEquals(401,userRequest(first,user,"GET","/api/v1/users/me",Map.of(),null).statusCode());
        clock.advance(60_000);
        assertEquals(200,first.post("/api/v1/auth/otp/send",Map.of("phone","13800000001"),first.csrf()).statusCode());
        assertEquals(403,first.post("/api/v1/auth/otp/login",Map.of("phone","13800000001","code",sms.codes.get("+8613800000001")),first.csrf()).statusCode());
        assertEquals(200,userRequest(admin,user,"PATCH","/api/v1/admin/users/"+userId+"/status",Map.of("enabled",true),admin.csrf()).statusCode());
        assertFalse(second.session().path("authenticated").asBoolean());
        clock.advance(60_000); loginUser(first,sms,"13800000001");
        assertEquals(userId,first.session().path("user").path("id").asText());
      }
    }
  }

  @Test void exactUserAbsoluteLimitAndJdbcIdleExpiryForBothRoles() throws Exception {
    try (Database iamDb=new Database(); Database userDb=new Database()) {
      int userPort=freePort();
      try (var iam=start(iamDb,"prod",Map.of("iam.user-service-url","http://127.0.0.1:"+userPort),SmsConfiguration.class);
           var user=startUser(userDb,userPort,iam.getWebServer().getPort())) {
        var clock=iam.getBean(OtpPolicyMysqlTest.MutableClock.class);
        var sms=iam.getBean(RecordingSms.class);
        var browser=new Browser(iam);
        loginUser(browser,sms,"13800000001");
        var repository=iam.getBean(JdbcIndexedSessionRepository.class);
        String id=new String(Base64.getDecoder().decode(browser.cookie()),java.nio.charset.StandardCharsets.UTF_8);
        org.springframework.session.Session persisted=repository.findById(id);
        assertEquals(Duration.ofDays(30),persisted.getMaxInactiveInterval());
        assertNotNull(persisted.getAttribute(SessionLogin.AUTHENTICATED_AT));
        clock.advance(Duration.ofDays(180).toMillis()-1);
        assertTrue(browser.session().path("authenticated").asBoolean());
        clock.advance(1);
        assertFalse(browser.session().path("authenticated").asBoolean());
        loginUser(browser,sms,"13800000002");
        iamDb.jdbc.update("UPDATE SPRING_SESSION SET LAST_ACCESS_TIME=?,EXPIRY_TIME=? WHERE PRINCIPAL_NAME LIKE 'user:%'",System.currentTimeMillis()-Duration.ofDays(30).toMillis()+60_000,System.currentTimeMillis()+60_000);
        assertTrue(browser.session().path("authenticated").asBoolean());
        assertTrue(iamDb.jdbc.queryForObject("SELECT EXPIRY_TIME FROM SPRING_SESSION WHERE PRINCIPAL_NAME LIKE 'user:%'",Long.class)>System.currentTimeMillis()+Duration.ofDays(29).toMillis());
        iamDb.jdbc.update("UPDATE SPRING_SESSION SET LAST_ACCESS_TIME=?,EXPIRY_TIME=? WHERE PRINCIPAL_NAME LIKE 'user:%'",System.currentTimeMillis()-Duration.ofDays(30).toMillis(),System.currentTimeMillis()-1);
        assertFalse(browser.session().path("authenticated").asBoolean());
        var admin=new Browser(iam);
        assertEquals(200,admin.post("/api/v1/auth/admin/login",credentials(),admin.csrf()).statusCode());
        iamDb.jdbc.update("UPDATE SPRING_SESSION SET LAST_ACCESS_TIME=?,EXPIRY_TIME=? WHERE PRINCIPAL_NAME LIKE 'admin:%'",System.currentTimeMillis()-Duration.ofHours(24).toMillis()+60_000,System.currentTimeMillis()+60_000);
        assertTrue(admin.session().path("authenticated").asBoolean());
        assertTrue(iamDb.jdbc.queryForObject("SELECT EXPIRY_TIME FROM SPRING_SESSION WHERE PRINCIPAL_NAME LIKE 'admin:%'",Long.class)>System.currentTimeMillis()+Duration.ofHours(23).toMillis());
        iamDb.jdbc.update("UPDATE SPRING_SESSION SET LAST_ACCESS_TIME=?,EXPIRY_TIME=? WHERE PRINCIPAL_NAME LIKE 'admin:%'",System.currentTimeMillis()-Duration.ofHours(24).toMillis(),System.currentTimeMillis()-1);
        assertFalse(admin.session().path("authenticated").asBoolean());
      }
    }
  }

  @Test void userDirectoryOutageFailsClosedAndLogoutStillWorks() throws Exception {
    try (Database iamDb=new Database(); Database userDb=new Database()) {
      int port=freePort();
      try (var iam=start(iamDb,"prod",Map.of("iam.user-service-url","http://127.0.0.1:"+port),SmsConfiguration.class)) {
        var browser=new Browser(iam);
        String csrf;
        try (var user=startUser(userDb,port,iam.getWebServer().getPort())) {
          loginUser(browser,iam.getBean(RecordingSms.class),"13800000001"); csrf=browser.csrf();
        }
        assertEquals(503,browser.post("/internal/v1/iam/session/verify",Map.of(),csrf).statusCode());
        assertEquals(200,browser.post("/api/v1/auth/logout",Map.of(),csrf).statusCode());
        assertFalse(browser.session().path("authenticated").asBoolean());
      }
    }
  }

  static void loginUser(Browser browser,RecordingSms sms,String phone) throws Exception {
    var sent=browser.post("/api/v1/auth/otp/send",Map.of("phone",phone),browser.csrf());
    assertEquals(200,sent.statusCode(),sent.body());
    var login=browser.post("/api/v1/auth/otp/login",Map.of("phone",phone,"code",sms.codes.get("+86"+phone)),browser.csrf());
    assertEquals(200,login.statusCode(),login.body());
    assertTrue(browser.session().path("authenticated").asBoolean());
  }
  static HttpResponse<String> userRequest(Browser browser,ServletWebServerApplicationContext user,String method,String path,Map<String,Object> body,String csrf) throws Exception {
    String old=browser.base;
    try { browser.base="http://127.0.0.1:"+user.getWebServer().getPort(); return browser.send(method,path,body,csrf); }
    finally { browser.base=old; }
  }
  static int freePort() throws Exception { try (var socket=new java.net.ServerSocket(0)) { return socket.getLocalPort(); } }
  static ServletWebServerApplicationContext startUser(Database db,int port,int iamPort) {
    return (ServletWebServerApplicationContext) new SpringApplicationBuilder(com.xzwl.cloud.user.TemplateApplication.class).web(WebApplicationType.SERVLET)
        .run("--spring.config.location=classpath:/application.yml", "--spring.datasource.url="+db.url,
            "--spring.datasource.username=root", "--spring.datasource.password=", "--server.port="+port,
            "--server.address=127.0.0.1", "--logging.level.root=ERROR", "--spring.main.banner-mode=off",
            "--spring.flyway.locations=classpath:db/user-migration", "--user.iam-service-url=http://127.0.0.1:"+iamPort,
            "--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.session.SessionAutoConfiguration");
  }
  @TestConfiguration(proxyBeanMethods=false)
  static class SmsConfiguration {
    @Bean @Primary RecordingSms recordingSms() { return new RecordingSms(); }
    @Bean @Primary OtpPolicyMysqlTest.MutableClock testClock() { return new OtpPolicyMysqlTest.MutableClock(); }
  }
  static final class RecordingSms implements SmsOtpProvider {
    final Map<String,String> codes=new ConcurrentHashMap<>();
    public void send(String phone,String code) { codes.put(phone,code); }
  }
}
