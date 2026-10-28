package com.xzwl.cloud.promotion;

import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.promotion.application.production.AdminAgentDirectory;
import com.xzwl.cloud.promotion.application.production.PromotionStore;
import com.xzwl.cloud.promotion.interfaces.http.AdminAgentDirectoryController;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

class AdminAgentDirectoryTest {
  private static final Instant NOW=Instant.parse("2026-09-19T01:00:00Z");
  private static final String TODAY="2026-09-18T16:00:00.000000000Z";
  private static final String BEFORE="2026-09-18T15:59:59.000000000Z";
  @Test void verifiesGlobalFactsFullDirectorySearchCurrentRelationsMoneyAndAuthorization()throws Exception {
    var json=new ObjectMapper().findAndRegisterModules();var fail=new AtomicBoolean();var badIdentity=new AtomicBoolean();
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",exchange->{
      String path=exchange.getRequestURI().getPath();Object result;int status=200;
      if(fail.get()){result=Map.of();status=503;}
      else if(path.endsWith("/promotion-profiles")) {
        assertEquals("user-token",exchange.getRequestHeaders().getFirst("X-IAM-INTERNAL-TOKEN"));
        var ids=json.readTree(exchange.getRequestBody());assertTrue(ids.size()<=100);
        var rows=new ArrayList<Object>();for(var id:ids)rows.add(Map.of("user",Map.of("id",id.asText(),"nickname",id.asText().equals("a104")?"末页代理":"用户"+id.asText(),"phone",id.asText().equals("a104")?"+8613812349999":"+8613812340000","enabled",true),"registeredAt",id.asText().equals("d3")?TODAY:BEFORE));result=rows;
      } else if(path.endsWith("/account-identities")) {
        var rows=new ArrayList<Object>();for(var id:json.readTree(exchange.getRequestBody())) {
          if(badIdentity.get())continue;
          rows.add(Map.of("userId",id.asText(),"accountType",id.asText().equals("d1")?"API":"PERSONAL"));
        }result=rows;
      } else if(path.endsWith("/directory-facts")) {
        assertEquals("service-token",exchange.getRequestHeaders().getFirst("X-Service-Token"));
        var body=json.readTree(exchange.getRequestBody());
        boolean historical=json.treeToValue(body.path("to"),Instant.class).equals(Instant.parse(TODAY));
        var paid=new ArrayList<String>();for(var owner:body.path("owners"))if((historical?Set.of("d1"):Set.of("d1","d2")).contains(owner.path("ownerId").asText()))paid.add(owner.path("ownerId").asText());
        result=Map.of("paidOwners",paid);
      } else if(path.endsWith("/portal-summary"))result=Map.of("ownerId",path.split("/")[5],"summary",Map.of("totalRechargeAmount4","12.3456"));
      else {result=Map.of();status=404;}
      var bytes=json.writeValueAsBytes(result);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });server.start();
    String base="http://127.0.0.1:"+server.getAddress().getPort();
    try(var app=new SpringApplicationBuilder(TemplateApplication.class).web(WebApplicationType.NONE).run(
        "--spring.datasource.url=jdbc:h2:mem:agent-directory;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "--spring.datasource.username=sa","--spring.datasource.password=","--spring.datasource.driver-class-name=org.h2.Driver",
        "--spring.main.banner-mode=off","--logging.level.root=ERROR")) {
      var db=app.getBean(JdbcTemplate.class);var store=app.getBean(PromotionStore.class);
      for(int i=0;i<105;i++) {
        String id=String.format("a%03d",i),time=i==104?TODAY:BEFORE;
        db.update("INSERT INTO promoter_account VALUES(?,?,?, ?,?)",id,"C"+i,i==103?"DISABLED":"ENABLED",time,time);
        db.update("INSERT INTO promotion_commission_account(promoter_user_id,frozen4,available4,withdrawing4,withdrawn4) VALUES(?,1.1111,2.2222,3.3333,4.4444)",id);
      }
      db.update("INSERT INTO promotion_command_receipt VALUES('create-104','ADMIN_PROMOTER_CREATE','a104','opaque-secret',?)",TODAY);
      db.update("INSERT INTO promotion_command_receipt VALUES('later-create','ADMIN_PROMOTER_CREATE','a001','opaque-secret',?)",TODAY);
      db.update("INSERT INTO promotion_application VALUES('app-0','submit-0','a000','PERSONAL','ENABLED',?,1,?,'reason','channel',TRUE,?,'APPROVED','admin',?,NULL)",BEFORE,BEFORE,BEFORE,BEFORE);
      // The old a000 relation must not survive the current a104 binding.
      relation(db,"d1",1,"a000",BEFORE);relation(db,"d1",2,"a104",TODAY);relation(db,"d2",1,"a104",BEFORE);relation(db,"d3",1,"a104",TODAY);
      relation(db,"d4",1,"a000",BEFORE);relation(db,"d4",2,"ordinary-user",TODAY);
      // Every pending-balance action, including clawback debt and later repayment, is replayed exactly.
      String[] actions={"COMMISSION_FROZEN","COMMISSION_RELEASED","WITHDRAWAL_REQUESTED","WITHDRAWAL_PAID","RECHARGE_REFUND_CLAWBACK","CONSUMPTION_RELEASE_REVERSED","COMMISSION_FROZEN","COMMISSION_RELEASED","COMMISSION_RATE_CORRECTION","WITHDRAWAL_REQUESTED","WITHDRAWAL_REJECTED","WITHDRAWAL_REQUESTED","WITHDRAWAL_COMPENSATED","INCOME_ADJUSTMENT_INCREASE","INCOME_ADJUSTMENT_DECREASE","WITHDRAWAL_PAID"};
      String[] amounts={"100","100","80","80","60","10","100","70","10","10","10","5","5","3.1234","1.0001","2"};
      for(int i=0;i<actions.length;i++)db.update("INSERT INTO promotion_commission_ledger(promoter_user_id,sequence_no,action_type,amount4,source_event_id,funding_lot_id,created_at) VALUES('a000',?,?,?,?,?,?)",i+1,actions[i],new java.math.BigDecimal(amounts[i]),"history-"+i,i<6?"lot-one":"lot-two",BEFORE);
      // Exact midnight must not enter yesterday, regardless of variable ISO fractional precision.
      db.update("INSERT INTO promotion_commission_ledger(promoter_user_id,sequence_no,action_type,amount4,source_event_id,created_at) VALUES('a000',17,'COMMISSION_FROZEN',999,'boundary',?)",Instant.parse(TODAY).toString());
      db.update("INSERT INTO promotion_benefit_override VALUES(1,'a104',0.234567,0.012345)");
      var client=RestClient.builder().baseUrl(base).defaultHeader("X-Service-Token","service-token").build();
      var reader=new AdminAgentDirectoryReader(db,store,RestClient.builder().baseUrl(base).defaultHeader("X-IAM-INTERNAL-TOKEN","user-token").build(),client,client,Clock.fixed(NOW,ZoneOffset.UTC));
      var newest=reader.search(query(0,1,"",null,null,null,null),true);
      assertEquals("a104",newest.items().getFirst().userId(),"newest agent leads the directory");
      assertEquals("a000",reader.search(query(1,1,"",null,null,null,null),true).items().getFirst().userId(),"equal creation dates use stable IDs");
      assertEquals("a099",reader.search(query(100,1,"",null,null,null,null),true).items().getFirst().userId(),"keyset continues without gaps across the 100-row batch");
      var page=reader.search(query(0,1,"末页",null,null,null,null),true);
      assertEquals(1,page.total());assertEquals("a104",page.items().getFirst().userId());assertEquals("+8613812349999",page.items().getFirst().phone());
      assertEquals(105,page.summary().totalAgents());assertEquals(1,page.summary().todayNewAgents());assertEquals(1,page.summary().todayReferredUsers(),"old users rebound today are not new referrals");assertEquals(2,page.summary().paidDownlineUsers());assertEquals("699.9930",page.summary().pendingCommissionAmount4());
      assertEquals(new AdminAgentDirectory.Yesterday(104,104,2,1,"50.1233"),page.summary().yesterday());
      assertEquals(page.summary(),reader.search(query(0,1,"absent","ADMIN",true,Instant.parse(TODAY),NOW),true).summary(),"date/search/page filters never change global comparison facts");
      assertEquals(3,page.items().getFirst().invitedCount());assertEquals(2,page.items().getFirst().paidCount());assertEquals("37.0368",page.items().getFirst().totalRechargeAmount4());assertEquals("6.6666",page.items().getFirst().pendingCommissionAmount4());
      assertEquals("ADMIN",page.items().getFirst().source());
      var listed=page.items().getFirst();assertEquals(1,listed.personalPaidCount());assertEquals(0,listed.enterprisePaidCount());assertEquals(1,listed.apiPaidCount());
      assertEquals(Instant.parse(BEFORE),listed.registeredAt(),"user registration predates becoming an agent");assertEquals(Instant.parse(TODAY),listed.createdAt());
      assertEquals(105,reader.search(query(105,1,"",null,null,null,null),false).total());assertTrue(reader.search(query(105,1,"",null,null,null,null),false).items().isEmpty());
      assertEquals(1,reader.search(query(0,20,"9999",null,null,null,null),false).total());
      assertEquals(1,reader.search(query(0,20,"",null,false,null,null),false).total());
      assertEquals(1,reader.search(query(0,20,"","APPLICATION",null,null,null),false).total());
      assertEquals(103,reader.search(query(0,1,"","UNKNOWN",null,null,null),false).total());
      assertEquals(1,reader.search(query(0,20,"",null,null,Instant.parse(TODAY),NOW),false).total());
      var detail=reader.detail("a104",false);assertEquals("+86138****9999",detail.agent().phone());assertEquals("66.66",detail.paidPercent());assertEquals(1,detail.apiPaidCount());assertEquals(1,detail.personalPaidCount());assertEquals(0,detail.enterprisePaidCount());assertEquals("4.4444",detail.withdrawnAmount4());assertEquals("0.234567",detail.level1CashRate6());assertEquals("0.012345",detail.level2CashRate6());
      var downlines=reader.downlines("a104","d1","API",0,20,false);assertEquals(1,downlines.total());assertEquals("d1",downlines.items().getFirst().userId());assertTrue(downlines.items().getFirst().paid());assertEquals("12.3456",downlines.items().getFirst().totalRechargeAmount4());
      assertEquals(0,reader.downlines("a000","",null,0,20,true).total());
      assertFalse(json.writeValueAsString(reader.operations("a104",0,20)).contains("opaque-secret"));
      var controller=new AdminAgentDirectoryController(reader);
      assertEquals("+8613812349999",controller.detail(request("ADMIN"),"a104").agent().phone());
      assertEquals("+86138****9999",controller.detail(request("NORMAL_ADMIN"),"a104").agent().phone());
      assertEquals(403,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.detail(request("USER"),"a104")).getStatusCode().value());
      assertEquals(401,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.detail(request(null),"a104")).getStatusCode().value());
      badIdentity.set(true);assertThrows(IllegalStateException.class,()->reader.detail("a104",true));badIdentity.set(false);
      fail.set(true);assertThrows(org.springframework.web.client.RestClientException.class,()->reader.search(query(0,20,"",null,null,null,null),true));
    } finally {server.stop(0);}
  }
  @Test void rejectsInvalidFilters() {
    assertThrows(IllegalArgumentException.class,()->query(-1,20,"",null,null,null,null));
    assertThrows(IllegalArgumentException.class,()->query(0,101,"",null,null,null,null));
    assertThrows(IllegalArgumentException.class,()->query(0,20,"","UPGRADE",null,null,null));
    assertThrows(IllegalArgumentException.class,()->query(0,20,"",null,null,NOW,NOW));
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
  void productionDirectoryUsesConfiguredTransportAndTokens(boolean discovery)throws Exception {
    var requests=new java.util.concurrent.atomic.AtomicInteger();
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",exchange->{
      var json=new ObjectMapper();String path=exchange.getRequestURI().getPath();Object value;
      if(path.endsWith("/promotion-profiles")) {
        assertEquals("directory-user-token",exchange.getRequestHeaders().getFirst("X-IAM-INTERNAL-TOKEN"));
        var rows=new ArrayList<Object>();for(var id:json.readTree(exchange.getRequestBody()))rows.add(Map.of("user",Map.of("id",id.asText(),"nickname","Agent","phone","13812340000","enabled",true),"registeredAt",TODAY));value=rows;
      }else {
        assertEquals("directory-service-token",exchange.getRequestHeaders().getFirst("X-Service-Token"));
        if(path.endsWith("/directory-facts"))value=Map.of("paidOwners",List.of("downline"));
        else if(path.endsWith("/portal-summary"))value=Map.of("ownerId","downline","summary",Map.of("totalRechargeAmount4","12.3456"));
        else if(path.endsWith("/account-identities"))value=List.of(Map.of("userId","downline","accountType","PERSONAL"));
        else throw new AssertionError(path);
      }
      requests.incrementAndGet();var bytes=json.writeValueAsBytes(value);
      exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });server.start();
    String instance="http://127.0.0.1:"+server.getAddress().getPort();
    String url=discovery?"http://agent-directory-fixture":instance;
    try(var app=new SpringApplicationBuilder(TemplateApplication.class).web(WebApplicationType.NONE).run(
        "--spring.datasource.url=jdbc:h2:mem:agent-transport-"+discovery+";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "--spring.datasource.username=sa","--spring.datasource.password=","--spring.datasource.driver-class-name=org.h2.Driver",
        "--xzwl.discovery.enabled="+discovery,"--spring.cloud.discovery.client.simple.instances.agent-directory-fixture[0].uri="+instance,
        "--promotion.user-service-url="+url,"--promotion.payment-service-url="+url,"--promotion.billing-service-url="+url,
        "--promotion.user-service-token=directory-user-token","--abp.internal-token=directory-service-token",
        "--spring.main.banner-mode=off","--logging.level.root=ERROR")) {
      app.getBean(JdbcTemplate.class).update("INSERT INTO promoter_account VALUES('agent','AGENT','ENABLED',?,?)",TODAY,TODAY);
      app.getBean(JdbcTemplate.class).update("INSERT INTO promotion_commission_account(promoter_user_id,frozen4,available4,withdrawing4,withdrawn4) VALUES('agent',0,0,0,0)");
      relation(app.getBean(JdbcTemplate.class),"downline",1,"agent",TODAY);
      var directory=app.getBean(AdminAgentDirectory.class);
      var page=directory.search(query(0,20,"",null,null,null,null),true);
      assertEquals("Agent",page.items().getFirst().nickname());assertEquals("12.3456",page.items().getFirst().totalRechargeAmount4());
      assertEquals(1,directory.detail("agent",true).personalPaidCount());assertTrue(requests.get()>=6);
    }finally{server.stop(0);}
  }
  private static AdminAgentDirectory.Query query(int page,int size,String search,String source,Boolean enabled,Instant from,Instant to) {return new AdminAgentDirectory.Query(page,size,search,source,enabled,from,to);}
  private static void relation(JdbcTemplate db,String id,int version,String owner,String at) {db.update("INSERT INTO referral_binding(referred_user_id,version,command_id,direct_inviter_user_id,cash_tier,effective_at) VALUES(?,?,?,?,'NONE',?)",id,version,id+version,owner,at);}
  private static jakarta.servlet.http.HttpServletRequest request(String role) {
    return (jakarta.servlet.http.HttpServletRequest)java.lang.reflect.Proxy.newProxyInstance(AdminAgentDirectoryTest.class.getClassLoader(),new Class[]{jakarta.servlet.http.HttpServletRequest.class},(proxy,method,args)->switch(method.getName()) {
      case "getUserPrincipal" -> role==null?null:(java.security.Principal)()->"admin";
      case "isUserInRole" -> Objects.equals(role,args[0]);
      default -> null;
    });
  }
}
