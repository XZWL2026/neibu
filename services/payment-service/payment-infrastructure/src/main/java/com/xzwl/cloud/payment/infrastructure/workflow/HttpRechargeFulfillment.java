package com.xzwl.cloud.payment.infrastructure.workflow;
import com.xzwl.cloud.payment.application.workflow.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
public final class HttpRechargeFulfillment implements RechargeFulfillmentPort {
  private final RestClient billing,access,promotion;
  public HttpRechargeFulfillment(String billingUrl,String accessUrl,String promotionUrl,String token){this(RestClient.builder(),billingUrl,accessUrl,promotionUrl,token);}
  public HttpRechargeFulfillment(RestClient.Builder builder,String billingUrl,String accessUrl,String promotionUrl,String token){
    billing=client(builder,billingUrl,token);access=client(builder,accessUrl,token);promotion=client(builder,promotionUrl,token);
  }
  public void manageAccount(PaymentWorkflowStore.AccountChange change){
    access.post().uri("/internal/v1/access/admin-accounts").body(Map.of("requestId",change.requestId(),"userId",change.userId(),"accountType",change.accountType(),"createKey",change.createKey(),"operatorId",change.operatorId())).retrieve().toBodilessEntity();
  }
  public BigDecimal currentRate(){
    var rate=billing.get().uri("/internal/v1/billing/recharge-rate").retrieve().body(Rate.class);
    if(rate==null)throw new IllegalStateException("missing recharge rate");return new BigDecimal(rate.pointsPerYuan4());
  }
  public String existingAccountType(String owner){
    var identity=access.get().uri("/internal/v1/access/owners/{ownerId}/account-identity",owner).retrieve().body(OwnerIdentity.class);
    if(identity==null||!owner.equals(identity.ownerId()))throw new IllegalStateException("invalid account identity response");
    return identity.accountType()==null?null:com.xzwl.cloud.payment.domain.order.RechargeType.valueOf(identity.accountType()).name();
  }
  public String ensurePrimary(RechargeOrder order){
    var key=access.post().uri("/internal/v1/access/primary-keys")
      .body(Map.of("userId",order.userId(),"accountType",order.rechargeType().name(),"orderId",order.id(),"paidAt",order.paidAt().toString())).retrieve().body(Key.class);
    if(key==null||!order.userId().equals(key.ownerId())||!(order.rechargeType()==com.xzwl.cloud.payment.domain.order.RechargeType.API?"API".equals(key.accountType()):java.util.Set.of("PERSONAL","ENTERPRISE").contains(key.accountType()))||key.keyId()==null)
      throw new IllegalStateException("invalid primary key response");
    return key.keyId();
  }
  public void credit(RechargeOrder order,String key){
    billing.post().uri("/internal/v1/billing/recharges").body(Map.of("orderId",order.id(),"userId",order.userId(),"keyId",key,
      "amount4",order.amount4().toPlainString(),"rateSnapshot4",order.rateSnapshot4().toPlainString(),"paidAt",order.paidAt().toString()))
      .retrieve().toBodilessEntity();
  }
  public void promote(PaymentWorkflowStore.PromotionDelivery delivery){
    var order=delivery.order();
    var body=new java.util.HashMap<String,Object>();
    body.put("orderId",order.id());body.put("userId",order.userId());body.put("amount4",order.amount4().toPlainString());
    body.put("createdAt",order.createdAt().toString());body.put("paidAt",order.paidAt().toString());
    body.put("refundedAt",delivery.version()==2?delivery.occurredAt().toString():null);
    var response=promotion.post().uri("/internal/v1/promotions/paid-recharges").body(body).retrieve().body(PromotionReceipt.class);
    if(response==null || !order.id().equals(response.orderId()) || !java.util.Set.of("NONE","RESOLVED").contains(response.resolution()))
      throw new IllegalStateException("invalid promotion acknowledgment");
  }
  public record PromotionReceipt(String orderId,String resolution){}
  public void reserveRefund(RechargeOrder order){
    billing.post().uri("/internal/v1/billing/recharges/{id}/refund",order.id()).retrieve().toBodilessEntity();
  }
  private static RestClient client(RestClient.Builder builder,String url,String token){
    var f=new SimpleClientHttpRequestFactory();f.setConnectTimeout(Duration.ofSeconds(2));f.setReadTimeout(Duration.ofSeconds(5));
    return builder.clone().baseUrl(url).defaultHeader("X-Service-Token",token).requestFactory(f).build();
  }
  public record Rate(String pointsPerYuan4,long version){}
  public record OwnerIdentity(String ownerId,String accountType){}
  public record Key(String keyId,String ownerId,String accountType){}
}
