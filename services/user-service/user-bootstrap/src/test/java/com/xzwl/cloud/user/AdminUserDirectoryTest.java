package com.xzwl.cloud.user;

import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.user.application.identity.AdminUserDirectory;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AdminUserDirectoryTest {
  private static final String TOKEN="directory-test-internal-token-0000000000000";
  private static final Instant START=Instant.parse("2026-01-01T00:00:00Z");
  @Test void validatesFiltersAndExclusiveRanges() {
    assertEquals("API",query("API",null).accountType());
    assertThrows(IllegalArgumentException.class,()->query("UNKNOWN",null));
    assertThrows(IllegalArgumentException.class,()->new AdminUserDirectory.Query(0,101,"",null,null,null,null,null,START));
    assertThrows(IllegalArgumentException.class,()->new AdminUserDirectory.Query(0,20,"",null,null,null,null,START,START));
    assertEquals("UNSET",query("UNSET",null).accountType());
  }
  private static AdminUserDirectory.Query query(String type,String source) {
    return new AdminUserDirectory.Query(0,20,"",type,source,null,null,null,START);
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
  @EnabledIfEnvironmentVariable(named="IAM_TEST_MYSQL_URL",matches=".+")
  void batchesAllUsersBeforePaginationAndPreservesPermissionsAndFinancialFacts(boolean discovery)throws Exception {
    String root=System.getenv("IAM_TEST_MYSQL_URL");assertTrue(root.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"));
    String user=System.getenv("IAM_TEST_MYSQL_USERNAME"),password=System.getenv("IAM_TEST_MYSQL_PASSWORD");
    var admin=new JdbcTemplate(new DriverManagerDataSource(root,user,password));
    String schema="directory_facts_"+UUID.randomUUID().toString().replace("-","");
    admin.execute("CREATE DATABASE "+schema+" COLLATE utf8mb4_0900_bin");
    var json=new ObjectMapper().findAndRegisterModules();var batches=new AtomicInteger();var fail=new AtomicBoolean();var missingIdentity=new AtomicBoolean();var missingBalance=new AtomicBoolean();var apiMode=new AtomicBoolean();var unknownApi=new AtomicBoolean();var missingApiSince=new AtomicBoolean();
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",exchange->{
      String path=exchange.getRequestURI().getPath();Object result;int status=200;
      if(path.startsWith("/internal/v1/iam/")) {
        String cookie=exchange.getRequestHeaders().getFirst("Cookie");
        result=Map.of("authenticated",true,"roles",List.of(cookie),"user",Map.of("id","operator","username","operator"));
      } else if(!TOKEN.equals(exchange.getRequestHeaders().getFirst("X-Service-Token"))) {result=Map.of();status=401;}
      else if(fail.get()){result=Map.of();status=503;}
      else if(path.endsWith("recharge-rate"))result=Map.of("pointsPerYuan4","10.0000");
      else if(path.endsWith("directory-summary"))result=Map.of("ownerId",path.split("/")[5],"netConsumptionAmount4","0.9876","summary",Map.of("totalCalls",3,"debitedPoints4","22.0000","refundedPoints4","2.0000","netPoints4","20.0000","chargedAmount4","1.2345","totalRechargeAmount4","12.3456"));
      else {
        JsonNode body=json.readTree(exchange.getRequestBody());
        if(path.endsWith("directory-keys")) {
          batches.incrementAndGet();assertTrue(body.path("ownerIds").size()<=100);
          var list=new ArrayList<Object>();
          for(var id:body.path("ownerIds"))if(id.asText().equals("u204"))list.add(Map.of("ownerId","u204","keyId","key204","accountType","ENTERPRISE","enabled",false,"maskedKey","sk-safe****","matchesSearch",body.path("search").asText().equals("key204")));
          result=list;
        } else if(path.endsWith("account-identities")) {
          var list=new ArrayList<Object>();for(var id:body) {
            if(missingIdentity.get()&&id.asText().equals("u204"))continue;
            var identity=new HashMap<String,Object>();identity.put("userId",id.asText());
            identity.put("accountType",apiMode.get()&&id.asText().equals("u001")?"API":Set.of("u000","u202","u204").contains(id.asText())?(id.asText().equals("u000")?"API":"ENTERPRISE"):null);
            Instant month=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).withDayOfMonth(1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
            if(!missingApiSince.get())identity.put("apiSince",id.asText().equals("u000")?month.minusNanos(1000).toString():month.toString());
            list.add(identity);
          }result=list;
        } else if(path.endsWith("invitation-sources")) {
          var list=new ArrayList<String>();for(var id:body)if(Set.of("u203","u204").contains(id.asText()))list.add(id.asText());result=list;
        } else if(path.endsWith("api-daily-facts")) {
          Instant today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
          Instant from=json.convertValue(body.path("from"),Instant.class),to=json.convertValue(body.path("to"),Instant.class);
          boolean yesterday=from.equals(today.minusSeconds(86400));
          assertTrue(yesterday||from.equals(today));assertEquals(from.plusSeconds(86400),to);
          var values=new ArrayList<Object>();for(var owner:body.path("owners")) {
            String id=owner.path("ownerId").asText();boolean first=id.equals("u000"),second=id.equals("u001");
            var value=new HashMap<String,Object>(Map.of("ownerId",id,"todayCalls",yesterday?1:first?2:second?3:0,"todaySeconds",yesterday?5:first?8:second?12:0,"todayAmount4",yesterday?"0.1234":first?"1.2345":second?"2.0000":"0.0000","lastCallAt",from.plusSeconds(1).toString()));
            if(yesterday&&unknownApi.get()){value.put("todayCalls",null);value.put("todaySeconds",null);value.put("todayAmount4",null);}values.add(value);
          }result=values;
        } else if(path.endsWith("directory-facts")) {
          boolean includes=false;for(var owner:body.path("owners"))if(owner.path("ownerId").asText().equals("u204"))includes=true;
          boolean paid=includes&&json.convertValue(body.path("to"),Instant.class).isAfter(START.plusSeconds(43200));
          result=Map.of("paidOwners",paid?List.of("u204"):List.of(),"activeOwners",includes?List.of("u204"):List.of(),"balances",includes&&!missingBalance.get()?List.of(Map.of("keyId","key204","availablePoints4","12.3456")):List.of());
        } else {result=Map.of();status=404;}
      }
      byte[] bytes=json.writeValueAsBytes(result);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });server.start();
    String instance="http://127.0.0.1:"+server.getAddress().getPort();
    String dependency=discovery?"http://directory-fixture":instance;
    try(var app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(TemplateApplication.class).web(WebApplicationType.SERVLET)
        .run("--xzwl.discovery.enabled="+discovery,
            "--spring.cloud.discovery.client.simple.instances.directory-fixture[0].uri="+instance,
            "--spring.config.location=classpath:/application.yml","--spring.datasource.url="+root+schema+"?connectionTimeZone=UTC",
            "--spring.datasource.username="+user,"--spring.datasource.password="+password,"--server.port=0","--server.address=127.0.0.1",
            "--logging.level.root=ERROR","--spring.main.banner-mode=off","--abp.internal-token="+TOKEN,"--user.iam-service-url="+dependency,
            "--user.access-service-url="+dependency,"--user.payment-service-url="+dependency,"--user.billing-service-url="+dependency,"--user.promotion-service-url="+dependency)) {
      var db=app.getBean(JdbcTemplate.class);var rows=new ArrayList<Object[]>();
      for(int i=0;i<205;i++)rows.add(new Object[]{String.format("u%03d",i),String.format("+861380000%04d",i),"用户"+i,java.sql.Timestamp.from(START.plusSeconds(i))});
      db.batchUpdate("INSERT INTO user_identity(user_id,phone,nickname,created_at) VALUES(?,?,?,?)",rows);
      db.update("INSERT INTO admin_user_create_request(request_id,creator_id,phone,nickname,user_id) VALUES('created','admin','+8613800000204','用户204','u204')");
      String url="http://127.0.0.1:"+app.getWebServer().getPort()+"/api/v1/admin/users/";
      assertEquals(401,get(url+"directory",null).statusCode());assertEquals(403,get(url+"directory","user").statusCode());
      var response=get(url+"directory?limit=2&from=2026-01-01T00:03:20Z&to=2026-01-02T00:00:00Z","normal_admin");assertEquals(200,response.statusCode(),response.body());
      var page=json.readTree(response.body());assertEquals(3,batches.get());assertEquals(5,page.path("total").asInt());assertEquals(2,page.path("items").size());
      assertEquals(205,page.at("/summary/totalUsers").asInt());assertEquals(5,page.at("/summary/newUsers").asInt());assertEquals(1,page.at("/summary/paidUsers").asInt());assertEquals(204,page.at("/summary/unpaidUsers").asInt());assertEquals(1,page.at("/summary/activeUsers").asInt());
      assertEquals("u204",page.at("/items/0/id").asText());assertEquals("ADMIN",page.at("/items/0/source").asText());assertEquals("REFERRAL",page.at("/items/1/source").asText());
      assertEquals("DISABLED",page.at("/items/0/keyStatus").asText());assertEquals("1.2345",page.at("/items/0/availableAmount4").asText());assertTrue(page.at("/items/0/phone").asText().contains("****"));
      var beforePayment=json.readTree(get(url+"directory?limit=1&to=2026-01-01T12:00:00Z","normal_admin").body());
      assertEquals(205,beforePayment.at("/summary/totalUsers").asInt());assertEquals(0,beforePayment.at("/summary/paidUsers").asInt());assertEquals(205,beforePayment.at("/summary/unpaidUsers").asInt());
      var afterPayment=json.readTree(get(url+"directory?offset=100&limit=1&to=2026-01-01T12:00:01Z","normal_admin").body());
      assertEquals(1,afterPayment.at("/summary/paidUsers").asInt());assertEquals(204,afterPayment.at("/summary/unpaidUsers").asInt());
      var end=json.readTree(get(url+"directory?offset=203&limit=5","admin").body());assertEquals(205,end.path("total").asInt());assertEquals(2,end.path("items").size());assertEquals("u001",end.at("/items/0/id").asText());
      var keyPage=json.readTree(get(url+"directory?search=key204&source=ADMIN&keyStatus=DISABLED","admin").body());assertEquals(1,keyPage.path("total").asInt());assertEquals("+8613800000204",keyPage.at("/items/0/phone").asText());
      var enterprises=json.readTree(get(url+"directory?accountType=ENTERPRISE","admin").body());
      assertEquals(2,enterprises.path("total").asInt());assertEquals(1,enterprises.at("/summary/unpaidUsers").asInt(),"enterprise identity does not prove payment");
      var apiResponse=get(url+"directory?accountType=API","admin");assertEquals(200,apiResponse.statusCode());
      var apiPage=json.readTree(apiResponse.body());assertEquals(1,apiPage.path("total").asInt());assertEquals("u000",apiPage.at("/items/0/id").asText());
      assertEquals("u000",end.at("/items/1/id").asText());
      var phonePage=json.readTree(get(url+"directory?search=13800000000","admin").body());assertEquals(1,phonePage.path("total").asInt());assertEquals("API",phonePage.at("/items/0/accountType").asText());
      var apiDetail=get(url+"u000/directory-detail","normal_admin");assertEquals(200,apiDetail.statusCode());
      assertEquals("API",json.readTree(apiDetail.body()).at("/user/accountType").asText());assertTrue(json.readTree(apiDetail.body()).at("/user/phone").asText().contains("****"));
      var detail=json.readTree(get(url+"u204/directory-detail","normal_admin").body());assertEquals("12.3456",detail.at("/summary/totalRechargeAmount4").asText());assertEquals("1.2345",detail.at("/summary/chargedAmount4").asText());assertEquals("0.9876",detail.at("/summary/netConsumptionAmount4").asText());
      var users=app.getBean(com.xzwl.cloud.user.application.identity.UserIdentityStore.class);
      assertEquals("u204", users.create("created","admin","+8613800000204","用户204").id(), "legacy request replay still defaults to ADMIN");
      String remark="备".repeat(200);
      Instant beforeCreate=Instant.now().minusSeconds(1);
      var natural=users.create("natural","admin","+8613800000205","自然来源","NATURAL",remark);
      var referral=users.create("referral","admin","+8613800000206","手动代理来源","REFERRAL",null);
      var legacy=users.create("legacy","admin","+8613800000207","后台默认来源");
      assertEquals(natural.id(),users.create("natural","admin","+8613800000205","自然来源","NATURAL",remark).id());
      assertFalse(users.registeredAt(natural.id()).isBefore(beforeCreate));
      assertFalse(users.registeredAt(natural.id()).isAfter(Instant.now()), "database-generated registration timestamp must not appear in the future");
      var naturalPage=json.readTree(get(url+"directory?source=NATURAL&search="+natural.id(),"admin").body());
      assertEquals(1,naturalPage.path("total").asInt());assertEquals(remark,naturalPage.at("/items/0/remark").asText());
      assertEquals(0,json.readTree(get(url+"directory?source=ADMIN&search="+natural.id(),"admin").body()).path("total").asInt());
      var naturalDetail=json.readTree(get(url+natural.id()+"/directory-detail","normal_admin").body());
      assertEquals("NATURAL",naturalDetail.at("/user/source").asText());assertEquals(remark,naturalDetail.at("/user/remark").asText());
      assertEquals(1,json.readTree(get(url+"directory?source=REFERRAL&search="+referral.id(),"admin").body()).path("total").asInt());
      var legacyDetail=json.readTree(get(url+legacy.id()+"/directory-detail","admin").body());
      assertEquals("ADMIN",legacyDetail.at("/user/source").asText());assertTrue(legacyDetail.at("/user/remark").isNull());
      assertEquals("ADMIN",db.queryForObject("SELECT source FROM admin_user_create_request WHERE request_id='legacy'",String.class));
      apiMode.set(true);
      var apiDirectory=get(url.replace("/users/","/api-users/")+"directory?limit=1&from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z","normal_admin");
      assertEquals(200,apiDirectory.statusCode(),apiDirectory.body());
      var apiRows=json.readTree(apiDirectory.body());assertEquals(2,apiRows.path("total").asInt());assertEquals(1,apiRows.path("items").size());
      assertEquals(2,apiRows.at("/summary/totalApiUsers").asInt());assertEquals(5,apiRows.at("/summary/todayCalls").asInt());
      assertEquals(20,apiRows.at("/summary/todaySeconds").asInt());assertEquals("3.2345",apiRows.at("/summary/todayAmount4").asText());
      assertEquals("u001",apiRows.at("/items/0/user/id").asText());assertTrue(apiRows.at("/items/0/user/phone").asText().contains("****"));
      assertEquals(3,apiRows.at("/items/0/todayCalls").asInt());
      assertEquals(1,apiRows.at("/summary/previousMonthTotalApiUsers").asInt(-1));
      assertEquals(2,apiRows.at("/summary/yesterdayCalls").asInt(-1));assertEquals(10,apiRows.at("/summary/yesterdaySeconds").asInt(-1));
      assertEquals("0.2468",apiRows.at("/summary/yesterdayAmount4").asText());
      var filtered=json.readTree(get(url.replace("/users/","/api-users/")+"directory?search=u000&offset=1&limit=1","admin").body());
      assertEquals(0,filtered.path("items").size());assertEquals(1,filtered.at("/summary/previousMonthTotalApiUsers").asInt(-1));assertEquals(1,filtered.at("/summary/yesterdayCalls").asInt(-1));
      unknownApi.set(true);missingApiSince.set(true);
      var unknown=json.readTree(get(url.replace("/users/","/api-users/")+"directory","admin").body());
      for(String field:List.of("previousMonthTotalApiUsers","yesterdayCalls","yesterdaySeconds","yesterdayAmount4"))assertTrue(unknown.at("/summary/"+field).isNull(),field);
      unknownApi.set(false);missingApiSince.set(false);apiMode.set(false);
      missingIdentity.set(true);assertEquals(503,get(url+"directory","admin").statusCode());missingIdentity.set(false);
      missingBalance.set(true);assertEquals(503,get(url+"directory","admin").statusCode());missingBalance.set(false);
      fail.set(true);assertEquals(503,get(url+"directory","admin").statusCode());
    } finally {server.stop(0);admin.execute("DROP DATABASE "+schema);}
  }
  private static HttpResponse<String> get(String url,String role)throws Exception {
    var request=HttpRequest.newBuilder(URI.create(url));if(role!=null)request.header("Cookie",role);
    return HttpClient.newHttpClient().send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
  }
}
