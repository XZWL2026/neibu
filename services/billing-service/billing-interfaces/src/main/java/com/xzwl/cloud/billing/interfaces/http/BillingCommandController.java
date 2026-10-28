package com.xzwl.cloud.billing.interfaces.http;
import com.xzwl.cloud.billing.application.credit.*;
import com.xzwl.cloud.billing.application.recharge.*;
import com.xzwl.cloud.billing.application.ledger.*;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
public final class BillingCommandController {
  private final BillingLedgerStore ledger;private final KeyOwnershipPort keys;
  private final AdminCreditHandler admin;private final AdminCreditStore credits;
  private final CreditPaidRechargeHandler recharge;private final RechargeRateStore rate;
  public BillingCommandController(BillingLedgerStore ledger,KeyOwnershipPort keys,AdminCreditHandler admin,
      AdminCreditStore credits,CreditPaidRechargeHandler recharge,RechargeRateStore rate){
    this.ledger=ledger;this.keys=keys;this.admin=admin;this.credits=credits;this.recharge=recharge;this.rate=rate;
  }
  @PostMapping("/api/v1/admin/billing/credits")
  public BillingWalletController.Balance adminCredit(@RequestBody Credit body,HttpServletRequest request){
    if(request.getUserPrincipal()==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if(!BillingWalletController.admin(request))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    keys.find(body.keyId);
    if(body.remark==null||body.remark.isBlank()||body.remark.length()>2000)throw new IllegalArgumentException("remark required");
    Instant at=credits.findByRequestId(body.requestId).map(r->r.record().at()).orElseGet(Instant::now);
    return BillingWalletController.view(admin.handle(new AdminCreditCommand(body.requestId,body.keyId,body.keyId,decimal(body.points4),
        request.getUserPrincipal().getName(),at,body.remark)).balance());
  }
  @PostMapping("/api/v1/admin/billing/debits")
  public BillingWalletController.Balance debit(@RequestBody Debit body,HttpServletRequest request){
    if(request.getUserPrincipal()==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if(!BillingWalletController.admin(request))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    var key=keys.find(body.keyId);
    if(body.amount4==null || body.amount4.length()>64)throw new IllegalArgumentException("invalid amount");
    return BillingWalletController.view(ledger.debit(new BillingLedgerStore.Debit(body.requestId,body.keyId,
        key.ownerId(),request.getUserPrincipal().getName(),new BigDecimal(body.amount4),body.remark)));
  }
  @GetMapping("/internal/v1/billing/recharge-rate")public Rate currentRate(){var current=rate.load();return new Rate(current.pointsPerYuan4().toString(),current.version());}
  @PostMapping("/internal/v1/billing/recharges")
  public BillingWalletController.Balance recharge(@RequestBody Recharge body){
    var key=keys.find(body.keyId);if(!key.ownerId().equals(body.userId))throw new IllegalArgumentException("recharge owner mismatch");
    return BillingWalletController.view(recharge.handle(new CreditPaidRechargeCommand(body.orderId,body.userId,body.keyId,body.keyId,
        decimal(body.amount4),decimal(body.rateSnapshot4),Instant.parse(body.paidAt))).balance());
  }
  @PostMapping("/internal/v1/billing/consumptions")
  public BillingWalletController.Balance consume(@RequestBody Consumption body){
    if(!keys.find(body.keyId).enabled())throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return BillingWalletController.view(ledger.consume(body.keyId,body.operationId,decimal(body.points4)));
  }
  @PostMapping("/internal/v1/billing/refunds")
  public BillingWalletController.Balance refund(@RequestBody Refund body){
    return BillingWalletController.view(ledger.refund(body.keyId,body.refundId,body.operationId,decimal(body.points4)));
  }
  @PostMapping("/internal/v1/billing/recharges/{orderId}/refund")
  public BillingWalletController.Balance cashRefund(@PathVariable("orderId")String orderId){return BillingWalletController.view(ledger.reserveRechargeRefund(orderId));}
  private static Decimal4 decimal(String input){
    if(input==null||input.length()>64)throw new IllegalArgumentException("invalid decimal");
    var value=new BigDecimal(input);
    if(value.compareTo(new BigDecimal("0.0001"))<0||value.compareTo(BigDecimal.TEN.pow(34))>=0)throw new IllegalArgumentException("invalid decimal");
    return Decimal4.of(value);
  }
  public record Debit(String requestId,String keyId,String amount4,String remark){}
  public record Credit(String requestId,String keyId,String points4,String remark){}
  public record Recharge(String orderId,String userId,String keyId,String amount4,String rateSnapshot4,String paidAt){}
  public record Consumption(String keyId,String operationId,String points4){}
  public record Refund(String keyId,String refundId,String operationId,String points4){}
  public record Rate(String pointsPerYuan4,long version){}
}
