package com.xzwl.cloud.payment;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import com.xzwl.cloud.payment.application.workflow.*;
import com.xzwl.cloud.payment.domain.order.RechargeType;
import com.xzwl.cloud.payment.infrastructure.shouqianba.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="PAYMENT_TEST_MYSQL_URL",matches=".+")
class ShouqianbaProviderTest {
  final ObjectMapper json=new ObjectMapper();
  final PaymentWorkflowMysqlTest.MutableClock clock=new PaymentWorkflowMysqlTest.MutableClock();
  final Map<String,Map<String,String>> remote=new ConcurrentHashMap<>();
  final AtomicInteger activations=new AtomicInteger(),checkins=new AtomicInteger(),creates=new AtomicInteger(),refunds=new AtomicInteger();
  final AtomicBoolean imageOnly=new AtomicBoolean();
  boolean legacyAlipay;
  final AtomicBoolean loseCreate=new AtomicBoolean(),loseRefund=new AtomicBoolean(),pendingRefund=new AtomicBoolean();
  final List<String> signatures=new CopyOnWriteArrayList<>();
  volatile String key="fixture-terminal-key",error=null;
  volatile String qr="https://pay.example.test/private-checkout",responseOverride=null;
  HttpServer server;KeyPair signing;ConfigurableApplicationContext app;JdbcTemplate root,sql;String schema,url;
  Map<String,Object> props;Map<String,String> env;

  @BeforeEach void start() throws Exception {
    var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);signing=generator.generateKeyPair();
    server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",this::respond);server.start();
    url=System.getenv("PAYMENT_TEST_MYSQL_URL");
    assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"));
    root=new JdbcTemplate(new DriverManagerDataSource(url,PaymentHttpLoopMysqlTest.databaseUser(),PaymentHttpLoopMysqlTest.databasePassword()));
    schema="sqb_"+UUID.randomUUID().toString().replace("-","");root.execute("CREATE DATABASE "+schema+" COLLATE utf8mb4_0900_bin");
    props=Map.of("spring.datasource.url",url+schema,"spring.datasource.username",PaymentHttpLoopMysqlTest.databaseUser(),
      "spring.datasource.password",PaymentHttpLoopMysqlTest.databasePassword(),"spring.flyway.locations","filesystem:"+java.nio.file.Path.of("..","payment-infrastructure","src","main","resources","db","migration").toAbsolutePath(),
      "abp.internal-token",PaymentHttpLoopMysqlTest.TOKEN,"payment.user-internal-token",PaymentHttpLoopMysqlTest.TOKEN,"payment.worker-enabled","false");
    env=new HashMap<>(Map.of("SQB_API_DOMAIN","http://127.0.0.1:"+server.getAddress().getPort(),"SQB_MERCHANT_SN","fixture-merchant",
      "SQB_VENDOR_SN","fixture-vendor","SQB_VENDOR_KEY","fixture-vendor-key","SQB_APP_ID","fixture-app","SQB_CODE","fixture-code",
      "SQB_DEVICE_ID","fixture-device","SQB_NOTIFY_URL","https://merchant.example.test/callbacks/payment","SQB_PAYWAY","3",
      "SQB_PUBLIC_KEY",Base64.getEncoder().encodeToString(signing.getPublic().getEncoded())));
    env.put("SQB_DATA_ENCRYPTION_KEY",Base64.getEncoder().encodeToString(new byte[32]));
    boot();
  }
  void boot(){
    var environment=new StandardEnvironment();environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    environment.getPropertySources().addFirst(new MapPropertySource("test",props));
    app=new SpringApplicationBuilder(TemplateApplication.class).environment(environment).web(WebApplicationType.NONE)
      .initializers(c->{c.getBeanFactory().registerSingleton("sqbConfig",SqbConfig.fromEnvironment(env,true));c.getBeanFactory().registerSingleton("testFulfillment",new PaymentWorkflowMysqlTest.Fulfillment());})
      .run("--spring.config.location=classpath:/application.yml");
    sql=app.getBean(JdbcTemplate.class);
    assertInstanceOf(ShouqianbaPaymentProvider.class,app.getBean(PaymentProvider.class));
  }
  ShouqianbaPaymentProvider provider(){return new ShouqianbaPaymentProvider(SqbConfig.fromEnvironment(env,true),app.getBean(ShouqianbaStateStore.class),clock);}
  PaymentWorkflow workflow(PaymentProvider provider,PaymentWorkflowMysqlTest.Fulfillment fulfillment){
    return new PaymentWorkflow(app.getBean(PaymentWorkflowStore.class),provider,fulfillment,clock);
  }
  PaymentWorkflow.NewRecharge request(String name){return new PaymentWorkflow.NewRecharge(name,name,new BigDecimal("5000.3400"),RechargeType.API,null);}
  @AfterEach void stop(){if(app!=null)app.close();if(server!=null)server.stop(0);if(schema!=null)root.execute("DROP DATABASE "+schema);}

  @Test void alipayResponseAliasWorksForCheckoutQueryCallbackAndRefund()throws Exception {
    legacyAlipay=true;var fulfillment=new PaymentWorkflowMysqlTest.Fulfillment();var flow=workflow(provider(),fulfillment);
    var order=selected(flow,"alipay-alias","ALIPAY",200);String id=order.path("orderId").asText();
    assertTrue(order.path("checkoutUrl").asText().startsWith("https://"));assertEquals("1",remote.get(client(id)).get("payway"));
    assertEquals(id,selected(flow,"alipay-alias","ALIPAY",200).path("orderId").asText());assertEquals(1,creates.get());
    paid(id,clock.instant());byte[] raw=body(id);flow.callback(raw,headers(raw));flow.callback(raw,headers(raw));flow.processPending();
    assertEquals(Set.of(id),fulfillment.credits);flow.refund(id,"selection-owner");flow.processPending();
    assertEquals("REFUNDED",flow.get(id,"selection-owner").status());assertEquals(1,refunds.get());
    String wx=selected(flow,"wechat-alias-rejected","WECHAT",200).path("orderId").asText();paid(wx,clock.instant());
    var wrong=new HashMap<>(remote.get(client(wx)));wrong.put("payway","1");byte[] bad=json.writeValueAsBytes(wrong);
    assertThrows(IllegalArgumentException.class,()->flow.callback(bad,headers(bad)));
  }

  @Test void selectedMethodsPersistAndCannotChangeOnReplay()throws Exception {
    var fulfillment=new PaymentWorkflowMysqlTest.Fulfillment();var flow=workflow(provider(),fulfillment);
    var ali=selected(flow,"select-ali","ALIPAY",200);String aid=ali.path("orderId").asText();
    assertEquals("2",remote.get(client(aid)).get("payway"));
    assertEquals("ALIPAY",ali.path("paymentMethod").asText());
    selected(flow,"select-ali","WECHAT",409);
    assertEquals(aid,selected(flow,"select-ali-alias","ALIPAY",200).path("orderId").asText());
    var wx=selected(flow,"select-wx","WECHAT",200);String wid=wx.path("orderId").asText();
    assertNotEquals(aid,wid);assertEquals("3",remote.get(client(wid)).get("payway"));assertEquals(2,creates.get());
    selected(flow,"invalid-method","OTHER",400);
    assertEquals("ALIPAY",sql.queryForObject("SELECT payment_method FROM payment_order WHERE id=?",String.class,aid));
    app.close();boot();env.put("SQB_PAYWAY","2");flow=workflow(provider(),fulfillment);
    assertEquals(wid,selected(flow,"select-wx","WECHAT",200).path("orderId").asText());assertEquals(2,creates.get());
    paid(wid,clock.instant());byte[] raw=body(wid);
    var wrong=new HashMap<>(remote.get(client(wid)));wrong.put("payway","2");byte[] bad=json.writeValueAsBytes(wrong);
    var restarted=flow;assertThrows(IllegalArgumentException.class,()->restarted.callback(bad,headers(bad)));
    flow.callback(raw,headers(raw));flow.callback(raw,headers(raw));flow.processPending();
    assertEquals(Set.of(wid),fulfillment.credits);
    flow.refund(wid,"selection-owner");flow.processPending();
    assertEquals("REFUNDED",flow.get(wid,"selection-owner").status());assertEquals(1,refunds.get());
  }
  JsonNode selected(PaymentWorkflow flow,String requestId,String method,int status)throws Exception {
    var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new com.xzwl.cloud.payment.interfaces.http.PaymentController(flow,user->true))
      .setControllerAdvice(new com.xzwl.cloud.payment.interfaces.http.PaymentErrors()).build();
    var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/payments/recharges")
      .with(request->{request.addUserRole("USER");request.setUserPrincipal(()->"selection-owner");return request;})
      .contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId",requestId,"amount4","5000.34","accountType","API","paymentMethod",method))))
      .andReturn().getResponse();
    assertEquals(status,response.getStatus());return json.readTree(response.getContentAsString());
  }

  @Test void channelAmountLimitIsDistinctAndDoesNotContactProvider(){
    var failure=assertThrows(IllegalArgumentException.class,()->provider().validateAmount(new BigDecimal("100000000.00"),"CNY"));
    assertEquals("PAYMENT_CHANNEL_AMOUNT_LIMIT",failure.getMessage());assertEquals(0,creates.get());assertEquals(0,activations.get());
    provider().validateAmount(new BigDecimal("99999999.99"),"CNY");
    var flow=workflow(provider(),new PaymentWorkflowMysqlTest.Fulfillment());
    var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new com.xzwl.cloud.payment.interfaces.http.PaymentController(flow,user->true))
      .setControllerAdvice(new com.xzwl.cloud.payment.interfaces.http.PaymentErrors()).build();
    try{
      var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/payments/recharges")
        .with(request->{request.addUserRole("USER");request.setUserPrincipal(()->"limit-user");return request;})
        .contentType("application/json").content("{\"requestId\":\"channel-limit\",\"amount4\":\"100000000.00\",\"accountType\":\"API\"}")).andReturn().getResponse();
      assertEquals(400,response.getStatus());assertEquals("PAYMENT_CHANNEL_AMOUNT_LIMIT",json.readTree(response.getContentAsString()).path("code").asText());
    }catch(Exception e){throw new AssertionError(e);}
    assertEquals(0,creates.get());assertEquals(0,activations.get());
  }
  @Test void providerSingleTradeLimitUsesExistingHttpErrorWithoutCheckout() throws Exception {
    var actual=provider();
    // Activate normally first; the rejection belongs to an upstream business operation.
    actual.create(new PaymentProvider.Charge(java.util.UUID.randomUUID().toString(),new BigDecimal("200.0000"),"CNY",clock.instant().plusSeconds(300)));
    error="{\"result_code\":\"200\",\"biz_response\":{\"result_code\":\"FAIL\",\"error_code\":\"UPAY_SINGLE_TRADE_OVER_LIMIT\",\"error_code_standard\":\"EP17\",\"error_message\":\"SECRET merchant details\"}}";
    var flow=workflow(actual,new PaymentWorkflowMysqlTest.Fulfillment());
    var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new com.xzwl.cloud.payment.interfaces.http.PaymentController(flow,user->true))
      .setControllerAdvice(new com.xzwl.cloud.payment.interfaces.http.PaymentErrors()).build();
    var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/payments/recharges")
      .with(request->{request.addUserRole("USER");request.setUserPrincipal(()->"provider-limit-user");return request;})
      .contentType("application/json").content("{\"requestId\":\"provider-limit\",\"amount4\":\"200.00\",\"accountType\":\"PERSONAL\"}")).andReturn().getResponse();
    assertEquals(400,response.getStatus());
    assertEquals("PAYMENT_CHANNEL_AMOUNT_LIMIT",json.readTree(response.getContentAsString()).path("code").asText());
    assertFalse(response.getContentAsString().contains("SECRET"));assertEquals(1,creates.get());
    assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM payment_order WHERE checkout_url IS NOT NULL OR fulfilled=true",Integer.class));
  }
  @Test void activationExactSignatureConcurrentCreateCheckinAndEncryptedRestart() throws Exception {
    var fulfillment=new PaymentWorkflowMysqlTest.Fulfillment();var flow=workflow(provider(),fulfillment);
    var order=flow.create(request("one"));
    assertEquals(qr,order.checkoutUrl());assertEquals(1,activations.get());assertEquals(1,creates.get());
    PaymentWorkflowMysqlTest.race(4,()->flow.create(request("one")));
    assertEquals(1,creates.get());
    var data=remote.get(client(order.id()));assertEquals("500034",data.get("total_amount"));assertEquals("3",data.get("payway"));
    String database=sql.queryForList("SELECT payload FROM payment_provider_state",String.class).toString()+
      sql.queryForObject("SELECT checkout_url FROM payment_order WHERE id=?",String.class,order.id());
    assertFalse(database.contains(key));assertFalse(database.contains(qr));assertFalse(database.contains("fixture-vendor-key"));
    for(var signature:signatures)assertFalse(database.contains(signature));
    app.close();boot();
    assertEquals(qr,app.getBean(PaymentWorkflowStore.class).find(order.id()).orElseThrow().checkoutUrl());
    clock.now=clock.now.plusSeconds(86400);
    provider().query(new PaymentProvider.Charge(order.id(),order.amount4(),"CNY",order.expiresAt()));
    assertEquals(1,activations.get());assertEquals(1,checkins.get());
  }

  @Test void lostCreateResponsePollingAndRefundResponseRecoverAfterRestart() throws Exception {
    var fulfillment=new PaymentWorkflowMysqlTest.Fulfillment();var flow=workflow(provider(),fulfillment);loseCreate.set(true);
    var initialFlow=flow;
    assertThrows(PaymentProvider.Unavailable.class,()->initialFlow.create(request("lost")));
    String id=sql.queryForObject("SELECT id FROM payment_order",String.class);
    assertEquals(1,creates.get());
    app.close();boot();flow=workflow(provider(),fulfillment);
    var recovered=flow.create(request("lost"));assertEquals(id,recovered.id());assertNull(recovered.checkoutUrl());assertEquals(1,creates.get());
    paid(id,clock.instant());flow.processPending();flow.processPending();
    assertEquals(Set.of(id),fulfillment.credits);
    flow.refund(id,"lost");loseRefund.set(true);flow.processPending();
    assertEquals("REFUND_PENDING",flow.get(id,"lost").status());assertEquals(1,refunds.get());
    assertTrue(sql.queryForObject("SELECT attempts FROM payment_work WHERE order_id=?",Integer.class,id)>0);
    app.close();boot();clock.now=clock.now.plusSeconds(301);flow=workflow(provider(),fulfillment);flow.processPending();
    assertEquals("REFUNDED",flow.get(id,"lost").status());assertEquals(1,refunds.get());
    assertEquals(Set.of(id),fulfillment.reservations);
  }

  @Test void lostQrCanExpireThenCreateNewOrderWithoutDuplicatingExistingCharge() throws Exception {
    var flow=workflow(provider(),new PaymentWorkflowMysqlTest.Fulfillment());loseCreate.set(true);
    assertThrows(PaymentProvider.Unavailable.class,()->flow.create(request("lost-qr")));
    var recovered=flow.create(request("lost-qr"));assertNull(recovered.checkoutUrl());assertEquals(1,creates.get());
    remote.get(client(recovered.id())).put("order_status","PAY_CANCELED");flow.processPending();
    assertEquals("EXPIRED",flow.get(recovered.id(),"lost-qr").status());
    var retry=flow.create(new PaymentWorkflow.NewRecharge("new-attempt","lost-qr",new BigDecimal("5000.3400"),RechargeType.API,null));
    assertNotEquals(recovered.id(),retry.id());assertNotNull(retry.checkoutUrl());assertEquals(2,creates.get());
  }

  @Test void signedCallbacksCheckRawBytesOwnershipMoneyStatusAndReplay() throws Exception {
    var fulfillment=new PaymentWorkflowMysqlTest.Fulfillment();var flow=workflow(provider(),fulfillment);var order=flow.create(request("notify"));
    var store=app.getBean(PaymentWorkflowStore.class);var oldPoll=store.claim(order.id(),clock.instant()).orElseThrow();
    paid(order.id(),clock.instant());byte[] raw=body(order.id());var headers=headers(raw);
    assertThrows(IllegalArgumentException.class,()->flow.callback((new String(raw,StandardCharsets.UTF_8)+" ").getBytes(StandardCharsets.UTF_8),headers));
    assertThrows(IllegalArgumentException.class,()->flow.callback(raw,Map.of("Authorization",List.of("bad"))));
    assertThrows(IllegalArgumentException.class,()->flow.callback(raw,Map.of("Authorization",List.of(headers.get("Authorization").getFirst(),"other"))));
    for(var replacement:Map.of("total_amount","1235","client_sn","00000000000000000000000000000000","sn","9999999999999999","order_status","REFUNDED").entrySet()){
      var changed=new HashMap<>(remote.get(client(order.id())));changed.put(replacement.getKey(),replacement.getValue());
      byte[] bad=json.writeValueAsBytes(changed);
      assertThrows(RuntimeException.class,()->flow.callback(bad,headers(bad)),replacement.getKey());
    }
    var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
      new com.xzwl.cloud.payment.interfaces.http.PaymentController(flow,user->true))
      .setControllerAdvice(new com.xzwl.cloud.payment.interfaces.http.PaymentErrors()).build();
    for(int replay=0;replay<2;replay++){
      var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/callbacks/payment")
        .contentType("application/json").content(raw).header("Authorization",headers.get("Authorization").getFirst())).andReturn().getResponse();
      assertEquals(200,response.getStatus());assertEquals("success",response.getContentAsString());
    }
    PaymentWorkflowMysqlTest.race(4,()->flow.callback(raw,headers));flow.processPending();
    store.complete(oldPoll,"");assertEquals("PAID",flow.get(order.id(),"notify").status());
    assertEquals(Set.of(order.id()),fulfillment.credits);
    assertEquals(1,sql.queryForObject("SELECT COUNT(*) FROM payment_work WHERE stage='FULFILL'",Integer.class));
    flow.refund(order.id(),"notify");flow.processPending();
    assertEquals("REFUNDED",flow.callback(raw,headers).status());
    var other=new HashMap<>(env);other.put("SQB_MERCHANT_SN","different-merchant");
    var foreign=new ShouqianbaPaymentProvider(SqbConfig.fromEnvironment(other,true),app.getBean(ShouqianbaStateStore.class),clock);
    assertThrows(RuntimeException.class,()->foreign.verify(raw,headers));
  }

  @Test void canceledOrdersStopPollingButUnknownStatusesStayRetryable() throws Exception {
    var fulfillment=new PaymentWorkflowMysqlTest.Fulfillment();var flow=workflow(provider(),fulfillment);
    var canceled=flow.create(request("canceled"));remote.get(client(canceled.id())).put("order_status","PAY_CANCELED");
    flow.processPending();assertEquals("EXPIRED",flow.get(canceled.id(),"canceled").status());
    assertTrue(sql.queryForObject("SELECT completed FROM payment_work WHERE order_id=?",Boolean.class,canceled.id()));
    var unknown=flow.create(request("unknown"));remote.get(client(unknown.id())).put("order_status","PAY_ERROR");flow.processPending();
    assertFalse(sql.queryForObject("SELECT completed FROM payment_work WHERE order_id=?",Boolean.class,unknown.id()));
    assertTrue(fulfillment.credits.isEmpty());
  }

  @Test void latePaymentsRefundAndUnknownOutcomesNeverCredit() throws Exception {
    var fulfillment=new PaymentWorkflowMysqlTest.Fulfillment();var flow=workflow(provider(),fulfillment);var order=flow.create(request("late"));
    clock.now=order.expiresAt().plusSeconds(1);paid(order.id(),clock.instant());flow.processPending();
    assertEquals("REFUND_PENDING",flow.get(order.id(),"late").status());assertTrue(fulfillment.credits.isEmpty());
    pendingRefund.set(true);flow.processPending();assertEquals("REFUND_PENDING",flow.get(order.id(),"late").status());
    remote.get(client(order.id())).put("order_status","REFUNDED");remote.get(client(order.id())).put("net_amount","0");
    clock.now=clock.now.plusSeconds(301);flow.processPending();assertEquals("REFUNDED",flow.get(order.id(),"late").status());
    assertTrue(fulfillment.credits.isEmpty());
  }

  @Test void qrPayloadSupportsWechatAndImageFallbackWithoutUnsafeSchemes() throws Exception {
    var flow=workflow(provider(),new PaymentWorkflowMysqlTest.Fulfillment());
    qr="weixin://wxpay/bizpayurl?pr=fixture";
    assertEquals(qr,flow.create(request("wechat")).checkoutUrl());
    qr="weixin://wxpay/bizpayurl/up?pr=fixture";
    assertEquals(qr,flow.create(request("wechat-up")).checkoutUrl());
    qr="https://pay.example.test/image";imageOnly.set(true);
    assertEquals(qr,flow.create(request("image")).checkoutUrl());
    qr="javascript:alert(1)";
    assertThrows(PaymentProvider.Unavailable.class,()->flow.create(request("unsafe")));
  }

  @Test void rejectFractionalCurrencyMalformedOversizedAndSafeErrorParsing() throws Exception {
    var p=provider();String id=UUID.randomUUID().toString();
    assertThrows(IllegalArgumentException.class,()->p.create(new PaymentProvider.Charge(id,new BigDecimal("12.3456"),"CNY",clock.instant().plusSeconds(900))));
    assertThrows(IllegalArgumentException.class,()->p.create(new PaymentProvider.Charge(id,BigDecimal.ONE,"USD",clock.instant().plusSeconds(900))));
    assertEquals(0,activations.get());
    var flow=workflow(p,new PaymentWorkflowMysqlTest.Fulfillment());var order=flow.create(request("error"));
    error="{\"result_code\":\"500\",\"error_code\":\"INVALID_PARAMS\",\"error_message\":\"SECRET payment data\",\"biz_response\":{\"error_code\":\"ACCOUNT_BALANCE_NOT_ENOUGH\"}}";
    var failure=assertThrows(PaymentProvider.Unavailable.class,()->p.query(new PaymentProvider.Charge(order.id(),order.amount4(),"CNY",order.expiresAt())));
    assertTrue(failure.getMessage().contains("INVALID_PARAMS"));assertFalse(failure.toString().contains("SECRET"));assertNull(failure.getCause());
    // Official communication errors must remain actionable without exposing the response text.
    for(String code:List.of("INVALID_TERMINAL","ILLEGAL_SIGN","UNKNOWN_SYSTEM_ERROR","REQUEST_FAIL","PAY_STATUS_CLOSED",
        "TERMINAL_NOT_EXISTS","INVALID_PARAMETER","MAINTENANCE_INPROGRESS")){
      error=json.writeValueAsString(Map.of("result_code","400","error_code",code,"error_message","SECRET merchant credentials"));
      failure=assertThrows(PaymentProvider.Unavailable.class,()->p.query(new PaymentProvider.Charge(order.id(),order.amount4(),"CNY",order.expiresAt())));
      assertEquals("SQB_"+code,failure.getMessage());assertNull(failure.getCause());
    }
    error="{\"result_code\":\"400\",\"error_code\":\"SECRET_UNKNOWN_CODE\",\"error_message\":\"SECRET\"}";
    assertEquals("SQB_PROVIDER_ERROR_REDACTED",assertThrows(PaymentProvider.Unavailable.class,
      ()->p.query(new PaymentProvider.Charge(order.id(),order.amount4(),"CNY",order.expiresAt()))).getMessage());
    error="{\"result_code\":\"200\",\"biz_response\":{\"result_code\":\"FAIL\",\"error_code_standard\":\"ACCOUNT_BALANCE_NOT_ENOUGH\",\"error_message\":\"SECRET\"}}";
    assertTrue(assertThrows(PaymentProvider.Unavailable.class,()->p.query(new PaymentProvider.Charge(order.id(),order.amount4(),"CNY",order.expiresAt()))).getMessage().contains("ACCOUNT_BALANCE_NOT_ENOUGH"));
    for(String bad:List.of("not-json"," ".repeat(1048577),"{\"result_code\":\"200\",\"result_code\":\"400\"}")){
      error=bad;assertThrows(PaymentProvider.Unavailable.class,()->p.query(new PaymentProvider.Charge(order.id(),order.amount4(),"CNY",order.expiresAt())));
    }
  }

  @Test void invalidActivationCodeNeverCreatesTerminalCredentialsOrCheckout() throws Exception {
    error="{\"result_code\":\"400\",\"error_code\":\"ACTIVATE_FAIL\",\"error_message\":\"SECRET activation data\"}";
    var flow=workflow(provider(),new PaymentWorkflowMysqlTest.Fulfillment());
    var failure=assertThrows(PaymentProvider.Unavailable.class,()->flow.create(request("invalid-activation")));
    assertEquals("SQB_ACTIVATE_FAIL",failure.getMessage());assertNull(failure.getCause());
    assertEquals(0,creates.get());
    assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM payment_provider_state WHERE id LIKE 'terminal:%'",Integer.class));
    assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM payment_order WHERE checkout_url IS NOT NULL OR fulfilled=true",Integer.class));
  }

  static String client(String id){return id.replace("-","");}
  void paid(String id,Instant when){var d=remote.get(client(id));d.put("order_status","PAID");d.put("status","SUCCESS");d.put("finish_time",Long.toString(when.toEpochMilli()));}
  byte[] body(String id)throws Exception{return json.writeValueAsBytes(remote.get(client(id)));}
  Map<String,List<String>> headers(byte[] raw)throws Exception {var signature=Signature.getInstance("SHA256withRSA");signature.initSign(signing.getPrivate());signature.update(raw);return Map.of("Authorization",List.of(Base64.getEncoder().encodeToString(signature.sign())));}
  void respond(HttpExchange exchange)throws java.io.IOException {
    try {
      byte[] raw=exchange.getRequestBody().readAllBytes();var request=json.readTree(raw);String path=exchange.getRequestURI().getPath();
      String secret=path.endsWith("activate")?"fixture-vendor-key":key;
      String sn=path.endsWith("activate")?"fixture-vendor":"fixture-terminal";
      var digest=MessageDigest.getInstance("MD5");digest.update(raw);String expected=sn+" "+HexFormat.of().withUpperCase().formatHex(digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
      if(!expected.equals(exchange.getRequestHeaders().getFirst("Authorization")))throw new AssertionError("signature bytes differ");
      signatures.add(expected);Object result;
      if(error!=null){reply(exchange,error);return;}
      if(path.endsWith("activate")){
        assertEquals("fixture-app",request.path("app_id").asText());assertEquals("fixture-device",request.path("device_id").asText());activations.incrementAndGet();
        result=Map.of("result_code","200","biz_response",Map.of("terminal_sn","fixture-terminal","terminal_key",key));
      }else if(path.endsWith("checkin")){
        checkins.incrementAndGet();key="fixture-rotated-key";
        result=Map.of("result_code","200","biz_response",Map.of("terminal_sn","fixture-terminal","terminal_key",key));
      }else{
        String client=request.path("client_sn").asText();var data=remote.get(client);
        if(path.endsWith("precreate")){
          if(data!=null)throw new AssertionError("duplicate precreate");creates.incrementAndGet();
          data=new ConcurrentHashMap<>();var fields=request.fields();while(fields.hasNext()){var e=fields.next();data.put(e.getKey(),e.getValue().asText());}
          if(legacyAlipay&&"2".equals(data.get("payway")))data.put("payway","1");
          data.put("sn",String.format("%016d",creates.get()));data.put("order_status","CREATED");data.put("status","IN_PROG");data.put("net_amount",data.get("total_amount"));data.put(imageOnly.getAndSet(false)?"qr_code_image_url":"qr_code",qr);
          remote.put(client,data);if(loseCreate.getAndSet(false)){exchange.close();return;}result=success("PRECREATE_SUCCESS",data);
        }else if(path.endsWith("refund")){
          assertNotNull(data);assertEquals("full",request.path("refund_request_no").asText());assertEquals(data.get("total_amount"),request.path("refund_amount").asText());
          refunds.incrementAndGet();data.put("order_status",pendingRefund.getAndSet(false)?"REFUND_INPROGRESS":"REFUNDED");data.put("net_amount",data.get("order_status").equals("REFUNDED")?"0":data.get("total_amount"));
          if(loseRefund.getAndSet(false)){exchange.close();return;}result=success(data.get("order_status").equals("REFUNDED")?"REFUND_SUCCESS":"REFUND_IN_PROGRESS",data);
        }else if(path.endsWith("query")){
          if(data==null)result=Map.of("result_code","200","biz_response",Map.of("result_code","FAIL","error_code","UPAY_ORDER_NOT_EXIST"));
          else{var queryData=new HashMap<>(data);queryData.remove("qr_code");queryData.remove("qr_code_image_url");result=success("SUCCESS",queryData);}
        }
        else throw new AssertionError("unexpected path");
      }
      reply(exchange,json.writeValueAsString(result));
    }catch(Throwable failure){byte[] bytes="fixture assertion failed".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(500,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();}
  }
  Object success(String code,Object data){return Map.of("result_code","200","biz_response",Map.of("result_code",code,"data",data));}
  void reply(HttpExchange exchange,String body)throws java.io.IOException {byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();}
}
