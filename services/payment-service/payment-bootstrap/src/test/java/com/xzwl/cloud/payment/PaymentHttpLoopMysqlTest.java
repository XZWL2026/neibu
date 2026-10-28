package com.xzwl.cloud.payment;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.xzwl.cloud.payment.application.workflow.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="PAYMENT_TEST_MYSQL_URL",matches=".+")
class PaymentHttpLoopMysqlTest {
  static final String TOKEN="test-only-service-token-0000000000000000";
  final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  final AtomicBoolean enabled=new AtomicBoolean(true);
  @Test void realControllersAndHttpAdaptersCloseThePaymentKeyWalletLoop()throws Exception{
    String root=System.getenv("PAYMENT_TEST_MYSQL_URL");
    if(!root.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"))throw new IllegalArgumentException("local MySQL required");
    var sql=new JdbcTemplate(new DriverManagerDataSource(root,databaseUser(),databasePassword()));
    String suffix=UUID.randomUUID().toString().replace("-","");
    var schemas=new ArrayList<String>();var apps=new ArrayList<ConfigurableApplicationContext>();
    HttpServer identity=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    identity.createContext("/",exchange->{
      String cookie=exchange.getRequestHeaders().getFirst("Cookie"),path=exchange.getRequestURI().getPath();
      int status=200;String result;
      if(path.startsWith("/internal/v1/users/")){
        if(!TOKEN.equals(exchange.getRequestHeaders().getFirst("X-IAM-INTERNAL-TOKEN"))){status=401;result="{}";}
        else {
          String target=path.substring(path.lastIndexOf('/')+1);
          if(!Set.of("user","manual-user","other-user","transfer-user","legacy-user","disabled-callback-timely","disabled-callback-late","disabled-poll-timely","disabled-poll-late").contains(target)){status=404;result="{}";}
          else result="{\"id\":\""+target+"\",\"enabled\":"+enabled.get()+"}";
        }
      }else if(cookie==null){status=401;result="{}";}
      else if(exchange.getRequestMethod().equals("POST")&&!"csrf".equals(exchange.getRequestHeaders().getFirst("X-CSRF-TOKEN"))){status=403;result="{}";}
      else{
        String role=cookie.contains("transfer-user")?"api_user":cookie.contains("normal")?"normal_admin":cookie.contains("admin")?"admin":"user";
        String id=role.equals("api_user")?"transfer-user":role.equals("user")?(cookie.contains("manual-user")?"manual-user":cookie.contains("other-user")?"other-user":"user"):role;
        result="{\"authenticated\":true,\"roles\":[\""+role+"\"],\"user\":{\"id\":\""+id+"\",\"username\":\"test\"},\"csrfToken\":\"csrf\",\"primary\":true}";
      }
      exchange.getResponseHeaders().set("Content-Type","application/json");
      byte[] bytes=result.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });
    identity.start();
    String identityUrl="http://127.0.0.1:"+identity.getAddress().getPort();
    try{
      for(String service:List.of("access","billing","payment")){
        String schema="abp_loop_"+service+"_"+suffix;sql.execute("CREATE DATABASE "+schema+" COLLATE utf8mb4_0900_bin");schemas.add(schema);
      }
      var access=start(com.xzwl.cloud.access.TemplateApplication.class,"access",root+schemas.get(0),
          Map.of("access.iam-service-url",identityUrl,"access.user-service-url",identityUrl,"access.user-internal-token",TOKEN,
            "access.key-encryption-key","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),null);apps.add(access);
      String accessUrl=url(access);
      var billing=start(com.xzwl.cloud.billing.TemplateApplication.class,"billing",root+schemas.get(1),
          Map.of("iam.service-url",identityUrl,"billing.access-service-url",accessUrl),null);apps.add(billing);
      String billingUrl=url(billing);
      var provider=new SignedProvider();
      var payment=start(TemplateApplication.class,"payment",root+schemas.get(2),
          Map.of("payment.iam-service-url",identityUrl,"payment.user-service-url",identityUrl,"payment.user-internal-token",TOKEN,
            "payment.access-service-url",accessUrl,"payment.billing-service-url",billingUrl,"payment.worker-enabled","false"),provider);apps.add(payment);
      String paymentUrl=url(payment);
      assertEquals(401,send(accessUrl,"GET","/api/v1/access/keys",null,Map.of()).statusCode());
      assertEquals("[]",send(accessUrl,"GET","/api/v1/access/keys",null,browser("user")).body());
      assertEquals(409,send(accessUrl,"POST","/api/v1/access/keys","{}",browser("user")).statusCode());
      String rate="{\"rechargePointsPerYuan4\":\"10.1234\",\"rechargeRateVersion\":1}";
      assertEquals(403,send(billingUrl,"PUT","/api/v1/settings/runtime",rate,browser("normal")).statusCode());
      assertEquals(200,send(billingUrl,"PUT","/api/v1/settings/runtime",rate,browser("admin")).statusCode());
      var options=send(paymentUrl,"GET","/api/v1/payments/recharge-options",null,browser("user"));
      assertEquals(200,options.statusCode(),options.body());assertTrue(json.readTree(options.body()).path("firstRecharge").asBoolean());
      assertTrue(json.readTree(options.body()).path("accountType").isNull());
      assertEquals(401,send(paymentUrl,"GET","/api/v1/payments/recharge-options",null,Map.of()).statusCode());
      assertEquals(403,send(paymentUrl,"GET","/api/v1/admin/payments/recharges",null,browser("user")).statusCode());
      assertEquals(400,send(paymentUrl,"GET","/api/v1/admin/payments/recharge-options?userId=missing",null,browser("admin")).statusCode());
      assertEquals(200,send(paymentUrl,"GET","/api/v1/admin/payments/recharge-options?userId=manual-user",null,browser("normal")).statusCode());
      String identities="/api/v1/admin/payments/account-identities";
      var paymentDb=payment.getBean(JdbcTemplate.class);
      long accountCount=paymentDb.queryForObject("SELECT COUNT(*) FROM payment_account",Long.class);
      var emptyTypes=send(paymentUrl,"GET",identities+"?userIds=manual-user,missing",null,browser("normal"));
      assertEquals(200,emptyTypes.statusCode(),emptyTypes.body());
      assertEquals("manual-user",json.readTree(emptyTypes.body()).get(0).path("userId").asText());
      assertTrue(json.readTree(emptyTypes.body()).get(0).path("accountType").isNull());
      assertEquals("missing",json.readTree(emptyTypes.body()).get(1).path("userId").asText());
      assertTrue(json.readTree(emptyTypes.body()).get(1).path("accountType").isNull());
      assertEquals(accountCount,paymentDb.queryForObject("SELECT COUNT(*) FROM payment_account",Long.class));
      assertEquals(401,send(paymentUrl,"GET",identities+"?userIds=user",null,Map.of()).statusCode());
      assertEquals(403,send(paymentUrl,"GET",identities+"?userIds=user",null,browser("user")).statusCode());
      for(String query:List.of("","?userIds=","?userIds=user,user","?userIds=%2F","?userIds="+String.join(",",java.util.stream.IntStream.range(0,101).mapToObj(i->"owner-"+i).toList())))
        assertEquals(400,send(paymentUrl,"GET",identities+query,null,browser("admin")).statusCode(),query);
      String enterpriseAttempt="{\"userId\":\"other-user\",\"requestId\":\"normal-enterprise\",\"amount4\":\"1000\",\"accountType\":\"ENTERPRISE\",\"enterpriseName\":\"Firm\",\"remark\":\"audit\"}";
      assertEquals(403,send(paymentUrl,"POST","/api/v1/admin/payments/recharges",enterpriseAttempt,browser("normal")).statusCode(),"normal admin cannot assign new enterprise identity through recharge");
      String manual="{\"userId\":\"manual-user\",\"requestId\":\"manual-http\",\"amount4\":\"50.0100\",\"accountType\":\"PERSONAL\",\"remark\":\"Bank transfer receipt\"}";
      assertEquals(403,send(paymentUrl,"POST","/api/v1/admin/payments/recharges",manual,browser("user")).statusCode());
      assertEquals(403,send(paymentUrl,"POST","/api/v1/admin/payments/recharges",manual,Map.of("Cookie","XZWL_SESSION=admin")).statusCode());
      var submission=send(paymentUrl,"POST","/api/v1/admin/payments/recharges",manual,browser("normal"));
      assertEquals(200,submission.statusCode(),submission.body());var manualOrder=json.readTree(submission.body());String manualId=manualOrder.path("orderId").asText();
      assertEquals("REVIEW_PENDING",manualOrder.path("status").asText());assertEquals("MANUAL",manualOrder.path("source").asText());
      assertEquals("manual-user",manualOrder.path("userId").asText());assertEquals("normal_admin",manualOrder.path("submittedBy").asText());
      assertTrue(manualOrder.path("confirmedBy").isNull());assertEquals("506.2712",manualOrder.path("expectedPoints4").asText());
      assertEquals(manualId,json.readTree(send(paymentUrl,"POST","/api/v1/admin/payments/recharges",manual,browser("normal")).body()).path("orderId").asText());
      String rejectBody=manual.replace("manual-user","other-user").replace("manual-http","reject-http");
      var rejectOrder=send(paymentUrl,"POST","/api/v1/admin/payments/recharges",rejectBody,browser("admin"));
      assertEquals(200,rejectOrder.statusCode());String rejectId=json.readTree(rejectOrder.body()).path("orderId").asText();
      String rejectPath="/api/v1/admin/payments/recharges/"+rejectId+"/reject";
      assertEquals(401,send(paymentUrl,"POST",rejectPath,null,Map.of()).statusCode());
      assertEquals(403,send(paymentUrl,"POST",rejectPath,null,browser("user")).statusCode());
      assertEquals(403,send(paymentUrl,"POST",rejectPath,null,Map.of("Cookie","XZWL_SESSION=admin")).statusCode());
      var rejection=send(paymentUrl,"POST",rejectPath,null,browser("normal"));assertEquals(200,rejection.statusCode(),rejection.body());
      var rejected=json.readTree(rejection.body());assertEquals("REJECTED",rejected.path("status").asText());assertEquals("normal_admin",rejected.path("rejectedBy").asText());assertFalse(rejected.path("rejectedAt").isNull());
      assertEquals(rejected,json.readTree(send(paymentUrl,"POST",rejectPath,null,browser("admin")).body()));
      assertEquals(409,send(paymentUrl,"POST","/api/v1/admin/payments/recharges/"+rejectId+"/confirm",null,browser("admin")).statusCode());
      assertEquals("[]",send(accessUrl,"GET","/api/v1/access/keys",null,browser("manual-user")).body());
      var otherHistory=json.readTree(send(paymentUrl,"GET","/api/v1/payments/recharges",null,browser("other-user")).body());
      assertEquals(1,otherHistory.size());assertEquals(rejectId,otherHistory.get(0).path("orderId").asText());
      assertEquals(403,send(paymentUrl,"GET","/api/v1/payments/recharges/"+manualId,null,browser("other-user")).statusCode());
      assertEquals(403,send(paymentUrl,"POST","/api/v1/admin/payments/recharges/"+manualId+"/confirm",null,browser("user")).statusCode());
      enabled.set(false);
      assertEquals(200,send(paymentUrl,"GET","/api/v1/admin/payments/recharge-options?userId=manual-user",null,browser("admin")).statusCode(),"admin can read disabled user for unified list");
      assertEquals(400,send(paymentUrl,"POST","/api/v1/admin/payments/recharges/"+manualId+"/confirm",null,browser("admin")).statusCode());enabled.set(true);
      var confirmation=send(paymentUrl,"POST","/api/v1/admin/payments/recharges/"+manualId+"/confirm",null,browser("admin"));
      assertEquals(200,confirmation.statusCode(),confirmation.body());assertFalse(json.readTree(confirmation.body()).path("fulfilled").asBoolean());
      // Lose the acknowledgement AFTER real Billing has committed: retry must not duplicate key or money.
      var realFulfillment=payment.getBean(RechargeFulfillmentPort.class);var loseCreditResponse=new AtomicBoolean(true);
      var interrupted=new RechargeFulfillmentPort(){
        public java.math.BigDecimal currentRate(){return realFulfillment.currentRate();}
        public String ensurePrimary(RechargeOrder order){return realFulfillment.ensurePrimary(order);}
        public void credit(RechargeOrder order,String key){realFulfillment.credit(order,key);if(loseCreditResponse.getAndSet(false))throw new IllegalStateException("test-only lost credit response");}
        public void reserveRefund(RechargeOrder order){realFulfillment.reserveRefund(order);}
      };
      new PaymentWorkflow(payment.getBean(PaymentWorkflowStore.class),provider,interrupted,Clock.systemUTC()).processPending();
      assertFalse(json.readTree(send(paymentUrl,"GET","/api/v1/payments/recharges/"+manualId,null,browser("manual-user")).body()).path("fulfilled").asBoolean());
      new PaymentWorkflow(payment.getBean(PaymentWorkflowStore.class),provider,interrupted,Clock.offset(Clock.systemUTC(),Duration.ofSeconds(10))).processPending();
      assertEquals(200,send(paymentUrl,"POST","/api/v1/admin/payments/recharges/"+manualId+"/confirm",null,browser("normal")).statusCode());
      var manualDone=json.readTree(send(paymentUrl,"GET","/api/v1/payments/recharges/"+manualId,null,browser("manual-user")).body());
      assertTrue(manualDone.path("fulfilled").asBoolean());assertEquals("admin",manualDone.path("confirmedBy").asText());
      var manualKeys=json.readTree(send(accessUrl,"GET","/api/v1/access/keys",null,browser("manual-user")).body());assertEquals(1,manualKeys.size());
      var manualWallet=send(billingUrl,"GET","/api/v1/billing/wallets/"+manualKeys.get(0).path("keyId").asText(),null,browser("manual-user"));
      assertEquals("506.2712",json.readTree(manualWallet.body()).path("availablePoints4").asText());
      // Real multipart receipt -> admin review -> Access key -> Billing wallet, with lost acknowledgement.
      String boundary="transfer-test-boundary";
      String multipart="--"+boundary+"\r\nContent-Disposition: form-data; name=\"requestId\"\r\n\r\ntransfer-http\r\n"
          +"--"+boundary+"\r\nContent-Disposition: form-data; name=\"amount4\"\r\n\r\n5000.12\r\n"
          +"--"+boundary+"\r\nContent-Disposition: form-data; name=\"remark\"\r\n\r\nBank receipt\r\n"
          +"--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"receipt.pdf\"\r\nContent-Type: application/pdf\r\n\r\n%PDF-1.7 receipt\r\n--"+boundary+"--\r\n";
      var transferRequest=HttpRequest.newBuilder(URI.create(paymentUrl+"/api/v1/payments/corporate-transfers"))
          .header("Content-Type","multipart/form-data; boundary="+boundary).header("Cookie","XZWL_SESSION=transfer-user")
          .header("X-CSRF-TOKEN","csrf").POST(HttpRequest.BodyPublishers.ofString(multipart)).build();
      var transferResponse=http.send(transferRequest,HttpResponse.BodyHandlers.ofString());
      assertEquals(201,transferResponse.statusCode(),transferResponse.body());
      var transfer=json.readTree(transferResponse.body());String transferId=transfer.path("orderId").asText();
      assertEquals("transfer-user",transfer.path("userId").asText());assertEquals("REVIEW_PENDING",transfer.path("status").asText());
      assertEquals("CORPORATE",transfer.path("source").asText());assertEquals("50618.2148",transfer.path("expectedPoints4").asText());
      assertEquals(transferId,json.readTree(http.send(transferRequest,HttpResponse.BodyHandlers.ofString()).body()).path("orderId").asText());
      String transferList="/api/v1/admin/payments/corporate-transfers",proofPath=transferList+"/"+transferId+"/proof";
      assertEquals(403,send(paymentUrl,"GET",transferList,null,browser("transfer-user")).statusCode());
      assertEquals(403,send(paymentUrl,"GET",proofPath,null,browser("transfer-user")).statusCode());
      assertEquals(403,send(paymentUrl,"POST","/api/v1/admin/payments/recharges/"+transferId+"/confirm",null,browser("transfer-user")).statusCode());
      var list=json.readTree(send(paymentUrl,"GET",transferList,null,browser("normal")).body());assertEquals(1,list.path("total").asInt());
      assertEquals("receipt.pdf",list.path("items").get(0).path("fileName").asText());
      var proofResponse=send(paymentUrl,"GET",proofPath,null,browser("normal"));assertEquals(200,proofResponse.statusCode());
      assertEquals("no-store",proofResponse.headers().firstValue("Cache-Control").orElse(""));
      assertEquals("%PDF-1.7 receipt",new String(Base64.getDecoder().decode(json.readTree(proofResponse.body()).path("base64").asText()),StandardCharsets.US_ASCII));
      assertEquals(403,send(paymentUrl,"POST","/api/v1/admin/payments/recharges/"+transferId+"/confirm",null,browser("normal")).statusCode());
      assertEquals(200,send(paymentUrl,"POST","/api/v1/admin/payments/recharges/"+transferId+"/confirm",null,browser("admin")).statusCode());
      loseCreditResponse.set(true);
      new PaymentWorkflow(payment.getBean(PaymentWorkflowStore.class),provider,interrupted,Clock.systemUTC()).processPending();
      new PaymentWorkflow(payment.getBean(PaymentWorkflowStore.class),provider,interrupted,Clock.offset(Clock.systemUTC(),Duration.ofSeconds(10))).processPending();
      assertEquals(200,send(paymentUrl,"POST","/api/v1/admin/payments/recharges/"+transferId+"/confirm",null,browser("admin")).statusCode());
      var transferOrder=payment.getBean(PaymentWorkflowStore.class).find(transferId).orElseThrow();assertTrue(transferOrder.fulfilled());
      var transferWallet=send(billingUrl,"GET","/api/v1/billing/wallets/"+transferOrder.keyId(),null,browser("admin"));
      assertEquals("50618.2148",json.readTree(transferWallet.body()).path("availablePoints4").asText());

      assertEquals(409,send(paymentUrl,"POST","/api/v1/payments/recharges/"+manualId+"/refund","{}",browser("manual-user")).statusCode());
      String manualOptions=send(paymentUrl,"GET","/api/v1/payments/recharge-options",null,browser("manual-user")).body();
      assertFalse(json.readTree(manualOptions).path("firstRecharge").asBoolean());assertEquals("PERSONAL",json.readTree(manualOptions).path("accountType").asText());
      // Existing Access identity still establishes subsequent-recharge thresholds, including a disabled key.
      var legacy=send(accessUrl,"POST","/internal/v1/access/primary-keys","{\"userId\":\"legacy-user\",\"accountType\":\"ENTERPRISE\",\"orderId\":\"legacy-first\"}",internal());
      assertEquals(200,legacy.statusCode(),legacy.body());
      String legacyKey=json.readTree(legacy.body()).path("keyId").asText();
      assertEquals(200,send(accessUrl,"PATCH","/api/v1/admin/access/keys/"+legacyKey+"/status","{\"enabled\":false}",browser("admin")).statusCode());
      paymentDb.update("INSERT INTO payment_account(user_id) VALUES('legacy-user')");
      long beforeLegacy=paymentDb.queryForObject("SELECT COUNT(*) FROM payment_account",Long.class);
      var legacyTypes=send(paymentUrl,"GET",identities+"?userIds=legacy-user,manual-user",null,browser("admin"));
      assertEquals(200,legacyTypes.statusCode(),legacyTypes.body());
      assertEquals("ENTERPRISE",json.readTree(legacyTypes.body()).get(0).path("accountType").asText());
      assertEquals("PERSONAL",json.readTree(legacyTypes.body()).get(1).path("accountType").asText());
      assertEquals(beforeLegacy,paymentDb.queryForObject("SELECT COUNT(*) FROM payment_account",Long.class));
      assertNull(paymentDb.queryForObject("SELECT locked_type FROM payment_account WHERE user_id='legacy-user'",String.class));
      assertFalse(paymentDb.queryForObject("SELECT recharge_completed FROM payment_account WHERE user_id='legacy-user'",Boolean.class));
      var directoryIds=send(paymentUrl,"POST","/internal/v1/payments/account-identities","[\"manual-user\",\"legacy-user\"]",internal());
      assertEquals(200,directoryIds.statusCode(),directoryIds.body());
      assertEquals(401,send(paymentUrl,"POST","/internal/v1/payments/account-identities","[\"manual-user\"]",Map.of()).statusCode());
      var directoryKeys=send(accessUrl,"POST","/internal/v1/access/directory-keys","{\"ownerIds\":[\"manual-user\",\"legacy-user\"],\"search\":\"\",\"secretSearch\":false}",internal());
      assertEquals(200,directoryKeys.statusCode(),directoryKeys.body());assertEquals(2,json.readTree(directoryKeys.body()).size());
      for(var directoryKey:json.readTree(directoryKeys.body())) {assertFalse(directoryKey.has("secret"));assertTrue(directoryKey.path("maskedKey").asText().endsWith("****"));}
      String directorySecret=access.getBean(com.xzwl.cloud.access.application.key.AccessKeyStore.class).findByOwner("manual-user").orElseThrow().secret();
      var secretSearch=send(accessUrl,"POST","/internal/v1/access/directory-keys",json.writeValueAsString(Map.of("ownerIds",List.of("manual-user"),"search",directorySecret,"secretSearch",true)),internal());
      assertEquals(200,secretSearch.statusCode());assertFalse(json.readTree(secretSearch.body()).get(0).path("matchesSearch").asBoolean(),"directory never matches full secret, even with the retired flag");
      var missingIdentity=send(paymentUrl,"POST","/internal/v1/payments/account-identities","[\"missing-owner\"]",internal());
      assertEquals(200,missingIdentity.statusCode());assertEquals(1,json.readTree(missingIdentity.body()).size());assertTrue(json.readTree(missingIdentity.body()).get(0).path("accountType").isNull());
      String directorySummaryPath="/internal/v1/billing/owners/manual-user/directory-summary?keyId="+manualKeys.get(0).path("keyId").asText();
      assertEquals(401,send(billingUrl,"GET",directorySummaryPath,null,Map.of()).statusCode());
      var directoryMoney=send(billingUrl,"GET",directorySummaryPath,null,internal());
      assertEquals(200,directoryMoney.statusCode(),directoryMoney.body());assertEquals("0.0000",json.readTree(directoryMoney.body()).path("netConsumptionAmount4").asText());
      var ownHistory=send(paymentUrl,"GET","/api/v1/admin/payments/recharges?userId=manual-user",null,browser("normal"));
      assertEquals(200,ownHistory.statusCode(),ownHistory.body());for(var recharge:json.readTree(ownHistory.body()))assertEquals("manual-user",recharge.path("userId").asText());
      paymentDb.update("UPDATE payment_account SET pending_request_id='batch-test-pending' WHERE user_id='manual-user'");
      try { assertEquals(409,send(paymentUrl,"GET",identities+"?userIds=legacy-user,manual-user",null,browser("admin")).statusCode()); }
      finally { paymentDb.update("UPDATE payment_account SET pending_request_id=NULL WHERE user_id='manual-user'"); }
      String legacyOptions=send(paymentUrl,"GET","/api/v1/admin/payments/recharge-options?userId=legacy-user",null,browser("admin")).body();
      assertEquals("ENTERPRISE",json.readTree(legacyOptions).path("accountType").asText());assertFalse(json.readTree(legacyOptions).path("firstRecharge").asBoolean());
      var lowAdmin=send(paymentUrl,"POST","/api/v1/admin/payments/recharges",manual.replace("manual-user","legacy-user").replace("manual-http","legacy-request").replace("PERSONAL","ENTERPRISE"),browser("admin"));
      assertEquals(200,lowAdmin.statusCode(),lowAdmin.body());assertEquals("REVIEW_PENDING",json.readTree(lowAdmin.body()).path("status").asText());
      String lowAdminId=json.readTree(lowAdmin.body()).path("orderId").asText();
      var lowApproved=send(paymentUrl,"POST","/api/v1/admin/payments/recharges/"+lowAdminId+"/confirm","{}",browser("admin"));
      assertEquals(200,lowApproved.statusCode(),lowApproved.body());assertEquals("PAID",json.readTree(lowApproved.body()).path("status").asText());
      String downgrade=manual.replace("manual-user","legacy-user").replace("manual-http","protected-downgrade").replace("50.0100","200.0000");
      assertEquals(403,send(paymentUrl,"POST","/api/v1/admin/payments/recharges",downgrade,browser("normal")).statusCode());
      var downgradeCreated=send(paymentUrl,"POST","/api/v1/admin/payments/recharges",downgrade,browser("admin"));
      assertEquals(200,downgradeCreated.statusCode(),downgradeCreated.body());
      String downgradeId=json.readTree(downgradeCreated.body()).path("orderId").asText();
      assertEquals(403,send(paymentUrl,"POST","/api/v1/admin/payments/recharges/"+downgradeId+"/confirm",null,browser("normal")).statusCode());
      assertEquals(200,send(paymentUrl,"POST","/api/v1/admin/payments/recharges/"+downgradeId+"/reject",null,browser("admin")).statusCode());
      String body="{\"requestId\":\"http-1\",\"amount4\":\"5000.12\",\"accountType\":\"API\"}";
      var opened=send(paymentUrl,"POST","/api/v1/payments/recharges",body,browser("user"));
      assertEquals(200,opened.statusCode(),opened.body());String id=json.readTree(opened.body()).path("orderId").asText();
      assertEquals(id,json.readTree(send(paymentUrl,"POST","/api/v1/payments/recharges",body,browser("user")).body()).path("orderId").asText());
      String callback=id+"|tx-1|5000.1200|"+Instant.now();
      assertEquals(400,send(paymentUrl,"POST","/callbacks/payment",callback,Map.of("X-Test-Signature","wrong")).statusCode());
      assertEquals(200,send(paymentUrl,"POST","/callbacks/payment",callback,Map.of("X-Test-Signature",sign(callback))).statusCode());
      var workflow=payment.getBean(PaymentWorkflow.class);workflow.processPending();
      var done=json.readTree(send(paymentUrl,"GET","/api/v1/payments/recharges/"+id,null,browser("user")).body());
      assertTrue(done.path("fulfilled").asBoolean(),done.toString());
      var keys=json.readTree(send(accessUrl,"GET","/api/v1/access/keys",null,browser("user")).body());
      assertEquals(1,keys.size());String key=keys.get(0).path("keyId").asText();
      var typed=send(paymentUrl,"GET",identities+"?userIds=user&userIds=manual-user&userIds=legacy-user",null,browser("admin"));
      assertEquals(200,typed.statusCode(),typed.body());
      assertEquals(List.of("API","PERSONAL","ENTERPRISE"),java.util.stream.StreamSupport.stream(json.readTree(typed.body()).spliterator(),false).map(row->row.path("accountType").asText()).toList());
      String wallet="/api/v1/billing/wallets/"+key;
      assertBalance(billingUrl,wallet,"50618.2148");
      send(paymentUrl,"POST","/callbacks/payment",callback,Map.of("X-Test-Signature",sign(callback)));workflow.processPending();
      assertBalance(billingUrl,wallet,"50618.2148");
      String consume="{\"keyId\":\""+key+"\",\"operationId\":\"task\",\"points4\":\"5.1234\"}";
      assertEquals(401,send(billingUrl,"POST","/internal/v1/billing/consumptions",consume,browser("user")).statusCode());
      assertEquals(200,send(billingUrl,"POST","/internal/v1/billing/consumptions",consume,internal()).statusCode());
      assertEquals(200,send(billingUrl,"POST","/internal/v1/billing/consumptions",consume,internal()).statusCode());
      assertBalance(billingUrl,wallet,"50613.0914");
      String refund="{\"keyId\":\""+key+"\",\"refundId\":\"undo\",\"operationId\":\"task\",\"points4\":\"5.1234\"}";
      assertEquals(200,send(billingUrl,"POST","/internal/v1/billing/refunds",refund,internal()).statusCode());
      assertBalance(billingUrl,wallet,"50618.2148");
      String secret=json.readTree(send(accessUrl,"GET","/api/v1/access/keys/"+key+"/secret",null,browser("user")).body()).path("secret").asText();
      String gift="{\"requestId\":\"gift-http\",\"keyId\":\""+key+"\",\"points4\":\"2.5678\",\"remark\":\"support\"}";
      assertEquals(403,send(billingUrl,"POST","/api/v1/admin/billing/credits",gift,browser("user")).statusCode());
      PaymentWorkflowMysqlTest.race(4,()->{
        var result=send(billingUrl,"POST","/api/v1/admin/billing/credits",gift,browser("normal"));
        assertEquals(200,result.statusCode(),result.body());return null;
      });
      assertBalance(billingUrl,wallet,"50620.7826");
      assertFalse(send(billingUrl,"GET",wallet+"/ledger",null,browser("user")).body().contains("ADMIN_CREDIT"));
      assertTrue(send(billingUrl,"GET",wallet+"/ledger",null,browser("admin")).body().contains("ADMIN_CREDIT"));
      String authorization="{\"secret\":\""+secret+"\",\"capability\":\"API\"}";
      assertEquals(200,send(accessUrl,"POST","/internal/v1/access/authorize",authorization,internal()).statusCode());
      send(accessUrl,"PATCH","/api/v1/access/keys/"+key+"/status","{\"enabled\":false}",browser("user"));
      assertEquals(403,send(accessUrl,"POST","/internal/v1/access/authorize",authorization,internal()).statusCode());
      send(accessUrl,"PATCH","/api/v1/access/keys/"+key+"/status","{\"enabled\":true}",browser("user"));
      enabled.set(false);
      assertEquals(403,send(accessUrl,"POST","/internal/v1/access/authorize",authorization,internal()).statusCode());
      enabled.set(true);
      assertEquals(202,send(paymentUrl,"POST","/api/v1/payments/recharges/"+id+"/refund","{}",browser("user")).statusCode());
      workflow.processPending();
      assertEquals("REFUNDED",json.readTree(send(paymentUrl,"GET","/api/v1/payments/recharges/"+id,null,browser("user")).body()).path("status").asText());
      assertBalance(billingUrl,wallet,"2.5678");
      assertEquals(1,provider.refunds.size());
      String creationOptions="/api/v1/admin/payments/api-creation-options";
      assertEquals(403,send(paymentUrl,"GET",creationOptions,null,browser("normal")).statusCode());
      var createOptions=send(paymentUrl,"GET",creationOptions,null,browser("admin"));assertEquals(200,createOptions.statusCode(),createOptions.body());
      var createRules=json.readTree(createOptions.body());assertTrue(createRules.path("userId").isNull());assertEquals("API",createRules.path("accountType").asText());assertTrue(createRules.path("firstRecharge").asBoolean());
      assertEquals(com.xzwl.cloud.payment.application.workflow.RechargeRules.rule(com.xzwl.cloud.payment.domain.order.RechargeType.API,true).minimumAmount4(),
          java.util.stream.StreamSupport.stream(createRules.path("rules").spliterator(),false).filter(r->r.path("accountType").asText().equals("API")).findFirst().orElseThrow().path("minimumAmount4").asText());
      String assignThroughRecharge=json.writeValueAsString(Map.of("requestId","api-unassigned","userId","other-user","amount4","5000.0000","accountType","API","remark","unassigned API"));
      assertEquals(403,send(paymentUrl,"POST","/api/v1/admin/payments/recharges",assignThroughRecharge,browser("normal")).statusCode());
      var primaryPending=send(paymentUrl,"POST","/api/v1/admin/payments/recharges",assignThroughRecharge,browser("admin"));assertEquals(200,primaryPending.statusCode(),primaryPending.body());
      assertEquals(403,send(paymentUrl,"POST","/api/v1/admin/payments/recharges/"+json.readTree(primaryPending.body()).path("orderId").asText()+"/confirm",null,browser("normal")).statusCode());
      String apiManual=json.writeValueAsString(Map.of("requestId","api-manual","userId","user","amount4","5000.0000","accountType","API","remark","API manual approval"));
      assertEquals(400,send(paymentUrl,"POST","/api/v1/admin/payments/recharges",apiManual.replace("5000.0000","0.0010"),browser("normal")).statusCode());
      var apiSubmitted=send(paymentUrl,"POST","/api/v1/admin/payments/recharges",apiManual,browser("normal"));
      assertEquals(200,apiSubmitted.statusCode(),apiSubmitted.body());
      String apiManualId=json.readTree(apiSubmitted.body()).path("orderId").asText();
      assertEquals("REVIEW_PENDING",json.readTree(apiSubmitted.body()).path("status").asText());
      assertBalance(billingUrl,wallet,"2.5678");
      assertEquals(apiManualId,json.readTree(send(paymentUrl,"POST","/api/v1/admin/payments/recharges",apiManual,browser("normal")).body()).path("orderId").asText());
      assertEquals(409,send(paymentUrl,"POST","/api/v1/admin/payments/recharges",apiManual.replace("5000.0000","5001.0000"),browser("normal")).statusCode());
      assertEquals(200,send(paymentUrl,"POST","/api/v1/admin/payments/recharges/"+apiManualId+"/confirm",null,browser("normal")).statusCode());
      workflow.processPending();workflow.processPending();
      assertBalance(billingUrl,wallet,"50619.5678");
      String apiDebit=json.writeValueAsString(Map.of("requestId","api-debit","keyId",key,"amount4","1.0000","remark","API correction"));
      assertEquals(403,send(billingUrl,"POST","/api/v1/admin/billing/debits",apiDebit,browser("user")).statusCode());
      assertEquals(200,send(billingUrl,"POST","/api/v1/admin/billing/debits",apiDebit,browser("normal")).statusCode());
      assertEquals(200,send(billingUrl,"POST","/api/v1/admin/billing/debits",apiDebit,browser("normal")).statusCode());
      assertBalance(billingUrl,wallet,"50609.4444");
      assertEquals(400,send(billingUrl,"POST","/api/v1/admin/billing/debits",apiDebit.replace("api-debit","api-overdraft").replace("1.0000","999999.0000"),browser("normal")).statusCode());
      assertBalance(billingUrl,wallet,"50609.4444");


      assertAll("disabled users retain verified payment and refund processing",
        ()->disabledPayment(payment,access,billing,provider,false,false),
        ()->disabledPayment(payment,access,billing,provider,false,true),
        ()->disabledPayment(payment,access,billing,provider,true,false),
        ()->disabledPayment(payment,access,billing,provider,true,true));
      // Personal -> enterprise -> personal, then replay the older enterprise delivery after a lost credit ACK.
      var keyStore=access.getBean(com.xzwl.cloud.access.application.key.AccessKeyStore.class);
      var originalKey=keyStore.findByOwner("manual-user").orElseThrow();
      var paymentStore=payment.getBean(PaymentWorkflowStore.class);
      String enterpriseBody=json.writeValueAsString(Map.of("requestId","choice-enterprise","amount4","2000.00","accountType","ENTERPRISE"));
      var enterpriseResponse=send(paymentUrl,"POST","/api/v1/payments/recharges",enterpriseBody,browser("manual-user"));
      assertEquals(200,enterpriseResponse.statusCode(),enterpriseResponse.body());
      var enterpriseOrder=paymentStore.find(json.readTree(enterpriseResponse.body()).path("orderId").asText()).orElseThrow();
      assertNull(enterpriseOrder.enterpriseName());assertEquals(com.xzwl.cloud.access.domain.key.AccountType.PERSONAL,keyStore.findByOwner("manual-user").orElseThrow().accountType());
      paymentStore.confirm(new PaymentProvider.VerifiedPayment(enterpriseOrder.id(),"choice-enterprise-tx",enterpriseOrder.amount4(),"CNY",Instant.now()),Instant.now());
      loseCreditResponse.set(true);
      new PaymentWorkflow(paymentStore,provider,interrupted,Clock.systemUTC()).processPending();
      assertFalse(paymentStore.find(enterpriseOrder.id()).orElseThrow().fulfilled());
      assertEquals(com.xzwl.cloud.access.domain.key.AccountType.ENTERPRISE,keyStore.findByOwner("manual-user").orElseThrow().accountType());
      // Simulate upgrading an old non-first order whose Access ACK had already committed before V3.
      access.getBean(JdbcTemplate.class).update("DELETE FROM access_recharge_application WHERE order_id=?",enterpriseOrder.id());
      String personalBody=json.writeValueAsString(Map.of("requestId","choice-personal","amount4","200.00","accountType","PERSONAL"));
      var personalResponse=send(paymentUrl,"POST","/api/v1/payments/recharges",personalBody,browser("manual-user"));
      assertEquals(200,personalResponse.statusCode(),personalResponse.body());
      var personalOrder=paymentStore.find(json.readTree(personalResponse.body()).path("orderId").asText()).orElseThrow();
      paymentStore.confirm(new PaymentProvider.VerifiedPayment(personalOrder.id(),"choice-personal-tx",personalOrder.amount4(),"CNY",Instant.now()),Instant.now());
      workflow.processPending();
      new PaymentWorkflow(paymentStore,provider,interrupted,Clock.offset(Clock.systemUTC(),Duration.ofSeconds(10))).processPending();
      assertTrue(paymentStore.find(enterpriseOrder.id()).orElseThrow().fulfilled());assertTrue(paymentStore.find(personalOrder.id()).orElseThrow().fulfilled());
      var switchedKey=keyStore.findByOwner("manual-user").orElseThrow();
      assertEquals(com.xzwl.cloud.access.domain.key.AccountType.PERSONAL,switchedKey.accountType());
      assertEquals(originalKey.id(),switchedKey.id());assertEquals(originalKey.secret(),switchedKey.secret());assertEquals(originalKey.firstRechargeOrderId(),switchedKey.firstRechargeOrderId());
      assertEquals("PERSONAL",paymentStore.accountType("manual-user"));
      var switchedWallet=send(billingUrl,"GET","/api/v1/billing/wallets/"+switchedKey.id(),null,browser("manual-user"));
      assertEquals("22777.7512",json.readTree(switchedWallet.body()).path("availablePoints4").asText());
      // Historical API identity comes from completed business events, not registration time or pending requests.
      paymentDb.update("INSERT INTO payment_account(user_id,locked_type) VALUES('api-history','API'),('api-legacy','API')");
      paymentDb.update("INSERT INTO payment_account_change(request_id,user_id,account_type,create_key,operator_id,completed,completed_at) VALUES('api-incomplete','api-history','API',true,'admin',false,'2026-01-01 00:00:00'),('api-complete','api-history','API',true,'admin',true,FROM_UNIXTIME(?))",new java.math.BigDecimal(Instant.parse("2026-08-31T15:59:59Z").getEpochSecond()).add(new java.math.BigDecimal("0.999999")));
      paymentDb.update("INSERT INTO payment_order(id,user_id,amount4,rate_snapshot4,recharge_type,created_at,expires_at,status,paid_at,fulfilled) VALUES('api-expired-refund','api-history',5000,10,'API',0,1,'REFUNDED','2026-01-01T00:00:00Z',false),('api-refund-pending','api-history',5000,10,'API',0,1,'REFUND_PENDING','2026-01-02T00:00:00Z',false)");
      var history=new org.springframework.transaction.support.TransactionTemplate(payment.getBean(org.springframework.transaction.PlatformTransactionManager.class)).execute(tx->{
        String zone=paymentDb.queryForObject("SELECT @@session.time_zone",String.class);
        try {paymentDb.execute("SET time_zone='+08:00'");return paymentStore.accountIdentities(List.of("api-history","api-legacy","user","manual-user"));}
        finally {paymentDb.update("SET time_zone=?",zone);}
      });
      assertEquals(Instant.parse("2026-08-31T15:59:59.999999Z"),history.get(0).apiSince());
      assertNull(history.get(1).apiSince(),"legacy identity without evidence stays unknown");
      assertEquals(paymentStore.history("user").stream().filter(o->o.paidAt()!=null&&o.rechargeType()==com.xzwl.cloud.payment.domain.order.RechargeType.API).map(o->o.paidAt()).min(Instant::compareTo).orElseThrow(),history.get(2).apiSince(),"first API payment survives refund and later recharge");
      assertNull(history.get(3).apiSince(),"non-API identities have no API opening date");
      access.close();
      var internalHistory=send(paymentUrl,"POST","/internal/v1/payments/account-identities","[\"api-history\",\"api-legacy\",\"user\",\"manual-user\"]",internal());
      assertEquals(200,internalHistory.statusCode(),internalHistory.body());
      var internalRows=json.readValue(internalHistory.body(),PaymentWorkflowStore.AccountIdentity[].class);
      assertArrayEquals(history.toArray(PaymentWorkflowStore.AccountIdentity[]::new),internalRows,"internal directory preserves API opening history without Access calls");
      assertEquals(200,send(paymentUrl,"GET",identities+"?userIds=user,manual-user,legacy-user",null,browser("admin")).statusCode(),"settled types need no Access calls");
      var unavailable=send(paymentUrl,"GET",identities+"?userIds=manual-user,missing",null,browser("admin"));
      assertEquals(503,unavailable.statusCode(),unavailable.body());
      assertEquals("PAYMENT_DEPENDENCY_UNAVAILABLE",json.readTree(unavailable.body()).path("code").asText());
    }finally{
      Collections.reverse(apps);for(var app:apps)app.close();identity.stop(0);
      for(String schema:schemas)sql.execute("DROP DATABASE "+schema);
    }
  }
  private void disabledPayment(ConfigurableApplicationContext payment,ConfigurableApplicationContext access,
      ConfigurableApplicationContext billing,SignedProvider provider,boolean poll,boolean late)throws Exception{
    String owner="disabled-"+(poll?"poll":"callback")+"-"+(late?"late":"timely");
    var store=payment.getBean(PaymentWorkflowStore.class);var fulfillment=payment.getBean(RechargeFulfillmentPort.class);
    Instant paidAt=Instant.now();
    // Use the production workflow with a controlled creation clock, so late payments need no wall-clock sleep.
    var creation=new PaymentWorkflow(store,provider,fulfillment,Clock.fixed(paidAt.minusSeconds(late?901:1),ZoneOffset.UTC));
    enabled.set(true);
    var order=creation.create(new PaymentWorkflow.NewRecharge(owner,owner,new java.math.BigDecimal("50.0000"),com.xzwl.cloud.payment.domain.order.RechargeType.PERSONAL,null));
    assertNull(store.accountType(owner));assertEquals("PENDING",order.status());
    enabled.set(false);
    try{
      if(poll){
        provider.polled.put(order.id(),new PaymentProvider.VerifiedPayment(order.id(),"tx-"+owner,order.amount4(),"CNY",paidAt));
        // This non-SQB test provider has no encrypted provider-state adapter; seed its production POLL outbox stage.
        payment.getBean(JdbcTemplate.class).update("INSERT INTO payment_work(order_id,stage) VALUES (?,'POLL')",order.id());
        payment.getBean(PaymentWorkflow.class).processPending();
      }else{
        String callback=order.id()+"|tx-"+owner+"|50.0000|"+paidAt;
        var response=send(url(payment),"POST","/callbacks/payment",callback,Map.of("X-Test-Signature",sign(callback)));
        assertEquals(200,response.statusCode(),owner+": "+response.body());
      }
      var confirmed=store.find(order.id()).orElseThrow();
      assertEquals("tx-"+owner,confirmed.transactionId(),owner);assertEquals(paidAt,confirmed.paidAt(),owner);
      assertEquals(late?"REFUND_PENDING":"PAID",confirmed.status(),owner);
      payment.getBean(PaymentWorkflow.class).processPending();
      var done=store.find(order.id()).orElseThrow();
      String path="/internal/v1/access/owners/"+owner;
      assertEquals(401,send(url(access),"GET",path+"/account-identity",null,Map.of()).statusCode());
      var identity=send(url(access),"GET",path+"/account-identity",null,internal());
      assertEquals(200,identity.statusCode(),identity.body());var metadata=json.readTree(identity.body());
      assertEquals(2,metadata.size());assertEquals(owner,metadata.path("ownerId").asText());
      assertEquals(403,send(url(access),"GET",path+"/primary-key",null,internal()).statusCode());
      if(late){
        assertEquals("REFUNDED",done.status());assertFalse(done.fulfilled());assertTrue(provider.refunds.contains("refund:"+order.id()));
        assertTrue(metadata.path("accountType").isNull());
        assertTrue(access.getBean(com.xzwl.cloud.access.application.key.AccessKeyStore.class).findByOwner(owner).isEmpty());
      }else{
        assertEquals("PERSONAL",metadata.path("accountType").asText());assertTrue(done.fulfilled());
        var key=access.getBean(com.xzwl.cloud.access.application.key.AccessKeyStore.class).findByOwner(owner).orElseThrow();
        var balance=send(url(billing),"GET","/api/v1/billing/wallets/"+key.id(),null,browser("admin"));
        assertEquals(200,balance.statusCode(),balance.body());assertEquals("506.1700",json.readTree(balance.body()).path("availablePoints4").asText());
        var authorization=json.writeValueAsString(Map.of("secret",key.secret(),"capability","MEDIA"));
        assertEquals(403,send(url(access),"POST","/internal/v1/access/authorize",authorization,internal()).statusCode());
        String consumption=json.writeValueAsString(Map.of("keyId",key.id(),"operationId",owner,"points4","1.0000"));
        assertEquals(403,send(url(billing),"POST","/internal/v1/billing/consumptions",consumption,internal()).statusCode());
      }
    }finally{enabled.set(true);}
  }
  private void assertBalance(String base,String path,String expected)throws Exception{
    var r=send(base,"GET",path,null,browser("user"));assertEquals(200,r.statusCode(),r.body());
    assertEquals(expected,json.readTree(r.body()).path("availablePoints4").asText());
  }
  private HttpResponse<String> send(String base,String method,String path,String body,Map<String,String> headers)throws Exception{
    var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15))
      .header("Content-Type",path.equals("/callbacks/payment")?"text/plain":"application/json")
      .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
    headers.forEach(request::header);return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
  }
  private static Map<String,String> browser(String session){return Map.of("Cookie","XZWL_SESSION="+session,"X-CSRF-TOKEN","csrf");}
  private static Map<String,String> internal(){return Map.of("X-Service-Token",TOKEN);}
  private static String url(ConfigurableApplicationContext app){return "http://127.0.0.1:"+((ServletWebServerApplicationContext)app).getWebServer().getPort();}
  private static ConfigurableApplicationContext start(Class<?> source,String service,String database,Map<String,Object> extra,PaymentProvider provider){
    var props=new HashMap<String,Object>(extra);props.put("spring.datasource.url",database);props.put("spring.datasource.username",databaseUser());props.put("spring.datasource.password",databasePassword());
    props.put("abp.internal-token",TOKEN);props.put("server.port","0");props.put("server.address","127.0.0.1");
    props.put("spring.flyway.locations","filesystem:"+Path.of("..","..",service+"-service",service+"-infrastructure","src","main","resources","db","migration").toAbsolutePath().normalize());
    var env=new StandardEnvironment();env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);env.getPropertySources().addFirst(new MapPropertySource("test",props));
    var builder=new SpringApplicationBuilder(source).environment(env).web(WebApplicationType.SERVLET);
    if(provider!=null)builder.initializers(ctx->ctx.getBeanFactory().registerSingleton("testPaymentProvider",provider));
    return builder.run("--spring.config.location=classpath:/application.yml");
  }
  static String sign(String body){
    try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("test-only-callback-key".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }catch(Exception e){throw new IllegalStateException(e);}
  }
  static String databaseUser(){return System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_USERNAME","root");}
  static String databasePassword(){return System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_PASSWORD","");}
  static class SignedProvider implements PaymentProvider {
    final Set<String> refunds=new HashSet<>();
    final Map<String,VerifiedPayment> polled=new HashMap<>();
    public QueryResult query(Charge charge){return new QueryResult(Optional.ofNullable(polled.get(charge.orderId())),false);}
    public Checkout create(Charge charge){return new Checkout(charge.orderId(),"https://pay.example.test/"+charge.orderId());}
    public VerifiedPayment verify(byte[] raw,Map<String,List<String>> headers){
      String body=new String(raw,StandardCharsets.UTF_8);
      String signature=headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase("X-Test-Signature")).findFirst().map(e->e.getValue().getFirst()).orElse("");
      if(!java.security.MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8),signature.getBytes(StandardCharsets.UTF_8)))throw new IllegalArgumentException("invalid signature");
      String[] parts=body.split("\\|");return new VerifiedPayment(parts[0],parts[1],new java.math.BigDecimal(parts[2]),"CNY",Instant.parse(parts[3]));
    }
    public String refund(Refund refund){refunds.add(refund.refundId());return "ref-"+refund.orderId();}
  }
}
