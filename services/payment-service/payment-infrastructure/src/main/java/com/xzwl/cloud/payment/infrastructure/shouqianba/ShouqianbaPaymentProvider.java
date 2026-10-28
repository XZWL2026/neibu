package com.xzwl.cloud.payment.infrastructure.shouqianba;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.xzwl.cloud.payment.application.workflow.PaymentProvider;
import java.io.*;
import java.math.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/** SQB Web API adapter. Provider payloads, credentials and signatures never enter exception messages. */
public final class ShouqianbaPaymentProvider implements PaymentProvider {
  private static final int MAX_RESPONSE=1024*1024;
  private static final Set<String> ERROR_CODES=Set.of("INVALID_PARAMS","INVALID_SIGN","INVALID_SIGNATURE","SIGN_ERROR",
    "INVALID_TERMINAL","ILLEGAL_SIGN","UNKNOWN_SYSTEM_ERROR","REQUEST_FAIL","PAY_STATUS_CLOSED",
    "TERMINAL_NOT_EXISTS","INVALID_PARAMETER","MAINTENANCE_INPROGRESS","ACTIVATE_FAIL",
    "CLIENT_SN_CONFLICT","UPAY_ORDER_NOT_EXIST","UPAY_ORDER_NOT_EXISTS","ACCOUNT_BALANCE_NOT_ENOUGH",
    "UPAY_REFUND_ORDER_NOOP","UPAY_REFUND_INVALID_ORDER_STATE","REFUNDABLE_AMOUNT_NOT_ENOUGH",
    "TRADE_TIMEOUT","TRADE_NOT_EXIST","EXTERNAL_SERVICE_EXCEPTION","UPAY_TERMINAL_NOT_EXISTS","UPAY_TERMINAL_STATUS_ABNORMAL");
  private final SqbConfig config;private final ShouqianbaStateStore store;private final Clock clock;
  private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  public ShouqianbaPaymentProvider(SqbConfig config,ShouqianbaStateStore store,Clock clock){this.config=config;this.store=store;this.clock=clock;}

  @Override public void validateAmount(BigDecimal amount,String currency){fen(amount,currency);}
  @Override public Checkout create(Charge charge){
    String client=clientSn(charge.orderId()),amount=fen(charge.amount4(),charge.currency());
    if(charge.expiresAt()==null||!clock.instant().isBefore(charge.expiresAt()))throw new IllegalArgumentException("payment expired");
    var terminal=ready(true);
    String payway=charge.paymentMethod()==null?config.payway:switch(charge.paymentMethod()){case ALIPAY->"2";case WECHAT->"3";};
    // Commit the merchant/app/device/amount binding before the first charge request (including timeouts).
    store.locked(orderKey(charge.orderId()),state->{
      if(state.isEmpty()){state.put("scope",config.scope);state.put("fen",amount);state.put("terminal",terminal.sn);state.put("payway",payway);}
      requireBinding(state,amount,terminal);
      if(charge.paymentMethod()!=null&&!payway.equals(state.get("payway")))throw invalid();return null;
    });
    return store.locked(orderKey(charge.orderId()),state->{
      requireBinding(state,amount,terminal);
      if(state.containsKey("url"))return new Checkout(state.get("sn"),state.get("url"));
      JsonNode data=queryOrder(client,terminal,true);
      if(data==null){
        var body=body(terminal,client);body.put("total_amount",amount);body.put("payway",state.getOrDefault("payway",payway));
        body.put("subject","视频创作充值");body.put("operator","system");body.put("notify_url",config.notifyUrl);
        data=business(request("/upay/v2/precreate",body,terminal.sn,terminal.key),"PRECREATE_SUCCESS").path("data");
      }
      bindResponse(state,data,client,amount,terminal);
      // Never create a replacement charge when the upstream order already exists or has a final outcome.
      if("PAID".equals(text(data,"order_status")))return new Checkout(state.get("sn"),null);
      if(!"CREATED".equals(text(data,"order_status")))throw unavailable("CHECKOUT_NOT_PENDING");
      String url=optional(data,"qr_code");if(url.isBlank())url=optional(data,"qr_code_image_url");
      // Query does not promise a QR payload. Keep the known order and its durable POLL work;
      // a missing link is never a reason to invent/reissue a second charge.
      if(url.isBlank())return new Checkout(state.get("sn"),null);
      url=url.strip();validateCheckout(url);state.put("url",url);return new Checkout(state.get("sn"),url);
    });
  }

  @Override public QueryResult query(Charge charge){
    String client=clientSn(charge.orderId()),amount=fen(charge.amount4(),charge.currency());
    boolean exists=store.locked(orderKey(charge.orderId()),state->{if(state.isEmpty())return false;requireScope(state,amount);return true;});
    if(!exists)return new QueryResult(Optional.empty(),!clock.instant().isBefore(charge.expiresAt())); // Worker must never activate an unregistered terminal/order.
    var terminal=ready(false);
    return store.locked(orderKey(charge.orderId()),state->{
      requireBinding(state,amount,terminal);var data=queryOrder(client,terminal,true);
      if(data==null)return new QueryResult(Optional.empty(),!clock.instant().isBefore(charge.expiresAt()));
      bindResponse(state,data,client,amount,terminal);
      String status=text(data,"order_status");
      if(Set.of("CANCELED","PAY_CANCELED").contains(status))return new QueryResult(Optional.empty(),true);
      if(!"PAID".equals(status))return new QueryResult(Optional.empty(),false);
      return new QueryResult(Optional.of(payment(charge.orderId(),data,amount)),false);
    });
  }

  @Override public VerifiedPayment verify(byte[] rawBody,Map<String,List<String>> headers){
    if(rawBody==null||rawBody.length==0||rawBody.length>65536)throw invalid();
    var values=headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase("Authorization")).flatMap(e->e.getValue().stream()).toList();
    if(values.size()!=1||values.getFirst().length()>2048)throw invalid();
    try{
      var signature=Signature.getInstance("SHA256withRSA");signature.initVerify(config.publicKey);signature.update(rawBody);
      if(!signature.verify(Base64.getDecoder().decode(values.getFirst())))throw invalid();
    }catch(GeneralSecurityException|IllegalArgumentException failure){throw invalid();}
    JsonNode data;try{data=json.readTree(rawBody);}catch(IOException failure){throw invalid();}
    if(data==null||!data.isObject())throw invalid();
    String client=text(data,"client_sn"),id=orderId(client),amount=amountText(data,"total_amount");
    // A platform signature alone does not establish merchant ownership. Bind to an order registered by
    // this deployment, and to the unique SQB sn returned over our authenticated terminal connection.
    String known=store.locked(orderKey(id),state->{requireScope(state,amount);return state.get("sn");});
    if(known==null){
      var terminal=ready(false);
      store.locked(orderKey(id),state->{
        requireBinding(state,amount,terminal);var queried=queryOrder(client,terminal,false);
        bindResponse(state,queried,client,amount,terminal);return null;
      });
    }
    return store.locked(orderKey(id),state->{
      requireScope(state,amount);
      if(!Objects.equals(state.get("sn"),text(data,"sn")))throw invalid();
      identityFields(data,state.get("terminal"));paymentMethodFields(data,state);
      return payment(id,data,amount);
    });
  }

  @Override public String refund(Refund refund){
    String client=clientSn(refund.orderId()),amount=fen(refund.amount4(),refund.currency());
    if(!("refund:"+refund.orderId()).equals(refund.refundId()))throw new IllegalArgumentException("only full order refunds are supported");
    var terminal=ready(false);
    return store.locked(orderKey(refund.orderId()),state->{
      requireBinding(state,amount,terminal);
      if(!Objects.equals(state.get("sn"),refund.transactionId()))throw invalid();
      var data=queryOrder(client,terminal,false);bindResponse(state,data,client,amount,terminal);
      if(refunded(data))return "sqb-refund:"+state.get("sn");
      if(!"PAID".equals(text(data,"order_status")))throw unavailable("REFUND_UNCONFIRMED");
      var body=body(terminal,client);body.put("refund_request_no","full");body.put("refund_amount",amount);body.put("operator","system");
      data=business(request("/upay/v2/refund",body,terminal.sn,terminal.key),"REFUND_SUCCESS").path("data");
      bindResponse(state,data,client,amount,terminal);
      if(!refunded(data))throw unavailable("REFUND_UNCONFIRMED");
      return "sqb-refund:"+state.get("sn");
    });
  }

  private boolean refunded(JsonNode data){return "REFUNDED".equals(text(data,"order_status"))&&"0".equals(amountText(data,"net_amount"));}
  private VerifiedPayment payment(String id,JsonNode data,String amount){
    if(!"PAID".equals(text(data,"order_status"))||!"SUCCESS".equals(text(data,"status")))throw invalid();
    String millis=text(data,"finish_time");if(!millis.matches("[0-9]{13}"))throw invalid();
    Instant paidAt=Instant.ofEpochMilli(Long.parseLong(millis));if(paidAt.isAfter(clock.instant()))throw invalid();
    String sn=text(data,"sn");if(!sn.matches("[A-Za-z0-9]{1,32}"))throw invalid();
    return new VerifiedPayment(id,sn,new BigDecimal(amount).movePointLeft(2).setScale(4),"CNY",paidAt);
  }
  private Terminal ready(boolean allowActivation){
    return store.locked("terminal:"+config.scope,state->{
      if(state.isEmpty()&&!config.seedSn.isEmpty()){
        state.put("sn",config.seedSn);state.put("key",config.seedKey);state.put("day",config.seedDay.toString());
      }
      LocalDate today=LocalDate.ofInstant(clock.instant(),ZoneId.of("Asia/Shanghai"));
      if(state.isEmpty()){
        if(!allowActivation||config.vendor.isBlank()||config.vendorKey.isBlank()||config.code.isBlank())throw unavailable("TERMINAL_NOT_ACTIVATED");
        var result=request("/terminal/activate",Map.of("app_id",config.app,"code",config.code,"device_id",config.device,"name",config.device),config.vendor,config.vendorKey);
        var biz=terminalBusiness(result);state.put("sn",text(biz,"terminal_sn"));state.put("key",text(biz,"terminal_key"));state.put("day",today.toString());
      }else if(!today.toString().equals(state.get("day"))){
        var result=request("/terminal/checkin",Map.of("terminal_sn",state.get("sn"),"device_id",config.device),state.get("sn"),state.get("key"));
        var biz=terminalBusiness(result);
        if(!state.get("sn").equals(text(biz,"terminal_sn")))throw unavailable("TERMINAL_ID_CHANGED");
        state.put("key",text(biz,"terminal_key"));state.put("day",today.toString());
      }
      return new Terminal(state.get("sn"),state.get("key"));
    });
  }
  private JsonNode queryOrder(String client,Terminal terminal,boolean missingAllowed){
    var result=request("/upay/v2/query",body(terminal,client),terminal.sn,terminal.key);
    if(missingAllowed&&"200".equals(optional(result,"result_code"))&&"FAIL".equals(optional(result.path("biz_response"),"result_code"))
        &&Set.of("UPAY_ORDER_NOT_EXIST","UPAY_ORDER_NOT_EXISTS").contains(optional(result.path("biz_response"),"error_code")))return null;
    return business(result,"SUCCESS").path("data");
  }
  private void bindResponse(Map<String,String> state,JsonNode data,String client,String amount,Terminal terminal){
    if(!client.equals(text(data,"client_sn"))||!amount.equals(amountText(data,"total_amount")))throw invalid();
    identityFields(data,terminal.sn);paymentMethodFields(data,state);
    String sn=text(data,"sn");if(!sn.matches("[A-Za-z0-9]{1,32}"))throw invalid();
    if(state.containsKey("sn")&&!sn.equals(state.get("sn")))throw invalid();state.put("sn",sn);
  }
  private void paymentMethodFields(JsonNode data,Map<String,String> state){
    if(data.has("payway")){String actual=text(data,"payway");
      if("1".equals(actual))actual="2"; // SQB returns both 1 and 2 for Alipay; requests remain 2.
      if(!Set.of("2","3").contains(actual)||state.containsKey("payway")&&!actual.equals(state.get("payway")))throw invalid();
      state.putIfAbsent("payway",actual);
    }
  }
  private void identityFields(JsonNode data,String terminal){
    for(var expected:Map.of("terminal_sn",terminal,"merchant_sn",config.merchant,"app_id",config.app).entrySet()){
      if(data.has(expected.getKey())&&!expected.getValue().equals(text(data,expected.getKey())))throw invalid();
    }
  }
  private void requireScope(Map<String,String> state,String amount){
    if(!config.scope.equals(state.get("scope"))||!amount.equals(state.get("fen")))throw invalid();
  }
  private void requireBinding(Map<String,String> state,String amount,Terminal terminal){requireScope(state,amount);if(!terminal.sn.equals(state.get("terminal")))throw invalid();}
  private static Map<String,String> body(Terminal terminal,String client){var body=new LinkedHashMap<String,String>();body.put("terminal_sn",terminal.sn);body.put("client_sn",client);return body;}

  private JsonNode request(String path,Map<String,String> body,String sn,String secret){
    HttpURLConnection connection=null;
    try{
      byte[] bytes=json.writeValueAsBytes(body);var md5=MessageDigest.getInstance("MD5");md5.update(bytes);
      String sign=HexFormat.of().withUpperCase().formatHex(md5.digest(secret.getBytes(StandardCharsets.UTF_8)));
      connection=(HttpURLConnection)config.endpoint.resolve(path).toURL().openConnection();
      connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(30_000);connection.setReadTimeout(30_000);
      connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(bytes.length);
      connection.setRequestProperty("Content-Type","application/json; charset=UTF-8");connection.setRequestProperty("Authorization",sn+" "+sign);
      try(var output=connection.getOutputStream()){output.write(bytes);}
      if(connection.getResponseCode()!=200)throw unavailable("HTTP_ERROR");
      try(var input=connection.getInputStream()){
        byte[] response=input.readNBytes(MAX_RESPONSE+1);if(response.length>MAX_RESPONSE)throw unavailable("RESPONSE_TOO_LARGE");
        JsonNode result=json.readTree(response);if(result==null||!result.isObject())throw unavailable("INVALID_RESPONSE");return result;
      }
    }catch(Unavailable failure){throw failure;}
    catch(Exception failure){throw unavailable("TRANSPORT_OR_RESPONSE_ERROR");}
    finally{if(connection!=null)connection.disconnect();}
  }
  private JsonNode terminalBusiness(JsonNode result){
    if(!"200".equals(optional(result,"result_code")))throw providerFailure(result);
    var biz=result.path("biz_response");if(optional(biz,"terminal_sn").isBlank()||optional(biz,"terminal_key").isBlank())throw unavailable("INVALID_TERMINAL_RESPONSE");
    return biz;
  }
  private JsonNode business(JsonNode result,String expected){
    if(!"200".equals(optional(result,"result_code"))||!expected.equals(optional(result.path("biz_response"),"result_code")))throw providerFailure(result);
    if(!result.path("biz_response").path("data").isObject())throw unavailable("INVALID_RESPONSE");return result.path("biz_response");
  }
  private Unavailable providerFailure(JsonNode result){
    // Preserve the legacy top-level > business > standard > result precedence, with no free-form text.
    String code=optional(result,"error_code");
    if(code.isBlank()&&!optional(result,"error_message").isBlank())return unavailable("PROVIDER_ERROR_REDACTED");
    var biz=result.path("biz_response");if(code.isBlank())code=optional(biz,"error_code");
    if(code.isBlank())code=optional(biz,"error_code_standard");
    if("UPAY_SINGLE_TRADE_OVER_LIMIT".equals(code))throw new PaymentProvider.ChannelAmountLimit();
    return unavailable(ERROR_CODES.contains(code)?code:"PROVIDER_ERROR_REDACTED");
  }
  private static String optional(JsonNode node,String field){var value=node.path(field);return value.isTextual()||value.isIntegralNumber()?value.asText():"";}
  private static String text(JsonNode node,String field){String value=optional(node,field);if(value.isEmpty()||value.length()>2048)throw invalid();return value;}
  private static String amountText(JsonNode node,String field){String value=text(node,field);if(!value.matches("0|[1-9][0-9]{0,9}"))throw invalid();return value;}
  private static String fen(BigDecimal amount,String currency){
    if(amount!=null&&amount.compareTo(new BigDecimal("99999999.99"))>0)throw new PaymentProvider.ChannelAmountLimit();
    try{
      if(!"CNY".equals(currency)||amount==null||amount.signum()<=0)throw new IllegalArgumentException();
      String value=amount.movePointRight(2).toBigIntegerExact().toString();if(value.length()>10)throw new IllegalArgumentException();return value;
    }catch(ArithmeticException|IllegalArgumentException failure){throw new IllegalArgumentException("SQB requires positive whole-fen CNY, at most 10 digits");}
  }
  private static String clientSn(String id){if(id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid();return id.replace("-","");}
  private static String orderId(String client){if(!client.matches("[0-9a-f]{32}"))throw invalid();return client.substring(0,8)+"-"+client.substring(8,12)+"-"+client.substring(12,16)+"-"+client.substring(16,20)+"-"+client.substring(20);}
  private static String orderKey(String id){return "order:"+id;}
  @Override public void validateCheckout(Checkout checkout){if(checkout.checkoutUrl()!=null)validateCheckout(checkout.checkoutUrl());}
  private static void validateCheckout(String value){
    try{
      URI uri=URI.create(value);
      boolean web="https".equalsIgnoreCase(uri.getScheme())&&uri.getHost()!=null;
      boolean qr="weixin".equals(uri.getScheme())&&"wxpay".equals(uri.getHost())&&Set.of("/bizpayurl","/bizpayurl/up").contains(uri.getPath())&&uri.getRawQuery()!=null;
      if(value.length()>2048||!(web||qr)||uri.getUserInfo()!=null||uri.getFragment()!=null)throw invalid();
    }
    catch(IllegalArgumentException failure){throw unavailable("INVALID_CHECKOUT");}
  }
  private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid SQB payment facts");}
  private static Unavailable unavailable(String code){return new Unavailable("SQB_"+code);}
  private static final class Terminal {final String sn,key;Terminal(String sn,String key){this.sn=sn;this.key=key;}}
}
