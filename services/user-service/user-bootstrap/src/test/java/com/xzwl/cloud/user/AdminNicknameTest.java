package com.xzwl.cloud.user;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.user.application.identity.*;
import com.xzwl.cloud.user.infrastructure.identity.*;
import com.xzwl.cloud.user.interfaces.http.UserIdentityController;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Real HTTP/security/controller/store validation; only the database mapper and IAM are substituted. */
@EnabledIfEnvironmentVariable(named="IAM_USER_INTERNAL_TOKEN", matches=".{32,}")
class AdminNicknameTest {
  @Test void renameEnforcesAdminCsrfValidationAndFieldIsolation() throws Exception {
    try (var app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(TestApp.class)
        .profiles("nickname-test").web(WebApplicationType.SERVLET).run("--spring.config.location=optional:classpath:/nickname-test.yml",
          "--server.address=127.0.0.1","--server.port=0","--logging.level.root=ERROR","--spring.main.banner-mode=off",
          "--spring.cloud.nacos.config.enabled=false","--spring.cloud.nacos.discovery.enabled=false",
          "--spring.cloud.nacos.config.import-check.enabled=false")) {
      String base="http://127.0.0.1:"+app.getWebServer().getPort()+"/api/v1/admin/users/";
      String url=base+"target/nickname";
      assertEquals(401,send(url,null,true,"{\"nickname\":\"no\"}").statusCode());
      for(String role:List.of("user","api_user","promoter"))
        assertEquals(403,send(url,role,true,"{\"nickname\":\"no\"}").statusCode());
      assertEquals(403,send(url,"admin",false,"{\"nickname\":\"no\"}").statusCode());
      for(String body:List.of("{}","{\"nickname\":null}","{\"nickname\":\"   \"}",
          "{\"nickname\":\""+"x".repeat(65)+"\"}","{\"nickname\":\"bad\\u007fname\"}"))
        assertEquals(400,send(url,"admin",true,body).statusCode(),body);
      assertEquals(404,send(base+"missing/nickname","admin",true,"{\"nickname\":\"new\"}").statusCode());
      var result=send(url,"normal_admin",true,"{\"nickname\":\" 新用户名 \",\"phone\":\"changed\",\"enabled\":true,\"avatarFileId\":\"changed\"}");
      assertEquals(200,result.statusCode(),result.body());
      var json=new ObjectMapper().readTree(result.body());
      assertEquals("新用户名",json.path("nickname").asText());
      assertTrue(json.path("phone").asText().contains("****"));
      var stored=app.getBean(UserIdentityStore.class).find("target");
      assertEquals("+8613800000001",stored.phone());assertEquals("avatar",stored.avatarFileId());
      assertFalse(stored.enabled());assertEquals(7,stored.authVersion());
      assertEquals(200,send(url,"admin",true,"{\"nickname\":\"新用户名\"}").statusCode());
      assertEquals(200,send(url,"admin",true,"{\"nickname\":\""+"名".repeat(64)+"\"}").statusCode());
    }
  }
  static HttpResponse<String> send(String url,String role,boolean csrf,String body) throws Exception {
    var request=HttpRequest.newBuilder(URI.create(url)).header("Content-Type","application/json");
    if(role!=null)request.header("Cookie",role);
    if(csrf)request.header("X-CSRF-TOKEN","csrf");
    return HttpClient.newHttpClient().send(request.method("PATCH",HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
  }
  @Configuration(proxyBeanMethods=false)
  @Profile("nickname-test")
  @EnableAutoConfiguration(excludeName={"org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
      "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"})
  static class TestApp {
    @Bean UserIdentityStore users(){
      var row=new UserIdentityEntity();row.userId="target";row.nickname="Original";row.phone="+8613800000001";
      row.avatarFileId="avatar";row.enabled=false;row.authVersion=7;
      var mapper=(UserIdentityMapper)Proxy.newProxyInstance(UserIdentityMapper.class.getClassLoader(),new Class<?>[]{UserIdentityMapper.class},(proxy,method,args)->{
        if(method.getName().equals("selectById"))return "target".equals(args[0])?row:null;
        if(method.getName().equals("rename")){if(!"target".equals(args[0]))return 0;row.nickname=(String)args[1];return 1;}
        throw new UnsupportedOperationException(method.getName());
      });
      var transactions=new AbstractPlatformTransactionManager(){
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object tx,TransactionDefinition definition){}
        protected void doCommit(DefaultTransactionStatus status){}
        protected void doRollback(DefaultTransactionStatus status){}
      };
      return new MyBatisUserIdentityStore(mapper,new TransactionTemplate(transactions));
    }
    @Bean UserIdentityController controller(UserIdentityStore users){return new UserIdentityController(users,(file,cookie)->{throw new AssertionError("Rename must not touch avatars");});}
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
      return new UserIdentityConfiguration().security(http,(cookie,csrf,mutation)->{
        if(cookie==null)throw new SessionVerifier.Rejected(401);
        if(mutation&&!"csrf".equals(csrf))throw new SessionVerifier.Rejected(403);
        return new SessionVerifier.Identity("operator",List.of(cookie));
      });
    }
  }
}
