package com.xzwl.cloud.promotion.interfaces.http;

import com.xzwl.cloud.promotion.application.production.PromotionOperations;
import com.xzwl.cloud.promotion.application.production.PromotionOperations.*;
import com.xzwl.cloud.promotion.application.production.PromotionStore.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public final class PromotionOperationsController {
  private final PromotionOperations operations;
  private final String token;
  public PromotionOperationsController(PromotionOperations operations,@Value("${abp.internal-token:${ABP_INTERNAL_TOKEN:}}") String token) {
    this.operations=operations;this.token=token;
  }
  @PostMapping("/internal/v1/promotions/consumption-settlements")
  public void settle(HttpServletRequest request,@RequestBody ConsumptionSettlement body) {
    String supplied=request.getHeader("X-Service-Token");
    if(token.isBlank()||supplied==null||!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    operations.settle(body);
  }
  @GetMapping("/api/v1/promotions/rewards")
  public RewardAccount rewards(HttpServletRequest request,@RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size) {
    String owner=identity(request);if(!request.isUserInRole("USER"))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return operations.rewards(owner,page,size);
  }
  @GetMapping("/api/v1/admin/promotions/operations/referral-adjustments")
  public ReferralAdjustmentPage referralAdjustments(HttpServletRequest request,@RequestParam(name="userId",required=false) String userId,
      @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size) {
    admin(request);return operations.referralAdjustments(userId,page,size);
  }
  @GetMapping("/api/v1/admin/promotions/operations/referrals/{userId}")
  public Attribution attribution(HttpServletRequest request,@PathVariable("userId") String userId) {admin(request);return operations.attribution(userId);}
  @PostMapping("/api/v1/admin/promotions/operations/referrals/{userId}")
  public Attribution rebind(HttpServletRequest request,@PathVariable("userId") String userId,@RequestBody RebindBody body) {
    return operations.rebind(new Rebind(body.commandId(),userId,body.inviterUserId(),body.expectedVersion(),body.reason(),admin(request)));
  }
  @GetMapping("/api/v1/admin/promotions/payouts")
  public PayoutPage payouts(HttpServletRequest request,@RequestParam(name="status",required=false) String status,
      @RequestParam(name="promoterUserId",required=false) String owner,@RequestParam(name="month",required=false) String month,
      @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size) {
    admin(request);return operations.payouts(status,owner,month,page,size);
  }
  @PostMapping("/api/v1/admin/promotions/payouts")
  public Payout create(HttpServletRequest request,@RequestBody CreatePayoutBody body) {
    return operations.createPayout(new CreatePayout(body.commandId(),body.withdrawalId(),body.promoterUserId(),body.settlementMonth(),body.amount4(),body.payoutAccountId(),admin(request),body.expectedDraftVersion()));
  }
  @GetMapping("/api/v1/admin/promotions/payouts/{id}/account")
  public PayoutDetails account(HttpServletRequest request,@PathVariable("id") String id) {return operations.payoutDetails(id,admin(request));}
  @GetMapping("/api/v1/admin/promotions/payout-accounts")
  public Page<PortalPayoutAccount> accounts(HttpServletRequest request,@RequestParam("promoterUserId") String owner,
      @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size) {
    admin(request);return operations.payoutAccounts(owner,page,size);
  }
  @PostMapping("/api/v1/admin/promotions/payouts/{id}/decision")
  public Payout decide(HttpServletRequest request,@PathVariable("id") String id,@RequestBody DecideBody body) {
    return operations.decidePayout(new DecidePayout(body.commandId(),id,body.approved(),body.reason(),admin(request),body.expectedAdjustmentVersion(),body.expectedAmount4()));
  }
  @PostMapping("/api/v1/admin/promotions/payouts/{id}/complete")
  public Payout complete(HttpServletRequest request,@PathVariable("id") String id,@RequestBody CompleteBody body) {
    return operations.completePayout(new CompletePayout(body.commandId(),id,body.transferReference(),body.paidAt(),admin(request)));
  }
  @GetMapping("/api/v1/admin/promotions/withdrawal-report")
  public PayoutPage report(HttpServletRequest request,@RequestParam(name="status",required=false) String status,
      @RequestParam(name="promoterUserId",required=false) String owner,@RequestParam(name="month",required=false) String month,
      @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size) {
    admin(request);return operations.payouts(status,owner,month,page,size);
  }
  @GetMapping("/api/v1/admin/promotions/agent-income")
  public AgentIncomePage agentIncome(HttpServletRequest request,@RequestParam("month") String month,
      @RequestParam(name="status",required=false) String status,@RequestParam(name="promoterUserId",required=false) String owner,
      @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size) {
    admin(request);return operations.agentIncome(month,status,owner,page,size);
  }
  @GetMapping("/api/v1/admin/promotions/agent-income/{userId}/consumptions")
  public IncomeConsumptionPage incomeConsumptions(HttpServletRequest request,@PathVariable("userId") String owner,@RequestParam("month") String month,
      @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size) {
    admin(request);return operations.incomeConsumptions(owner,month,page,size);
  }
  @GetMapping("/api/v1/admin/promotions/agent-income/{userId}/draft")
  public IncomeDraft incomeDraft(HttpServletRequest request,@PathVariable("userId") String owner,@RequestParam("month") String month,
      @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size) {
    admin(request);return operations.incomeDraft(owner,month,page,size);
  }
  @PostMapping("/api/v1/admin/promotions/agent-income/{userId}/draft")
  public IncomeDraft adjustIncomeDraft(HttpServletRequest request,@PathVariable("userId") String owner,@RequestParam("month") String month,@RequestBody AdjustIncomeBody body) {
    return operations.adjustIncomeDraft(new AdjustIncomeDraft(body.commandId(),owner,month,body.deltaAmount4(),body.reason(),body.expectedVersion(),admin(request)));
  }
  @GetMapping("/api/v1/admin/promotions/payouts/{id}/adjustments")
  public IncomeAdjustmentPage incomeAdjustments(HttpServletRequest request,@PathVariable("id") String id,
      @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size) {
    admin(request);return operations.incomeAdjustments(id,page,size);
  }
  @PostMapping("/api/v1/admin/promotions/payouts/{id}/adjustments")
  public Payout adjustIncome(HttpServletRequest request,@PathVariable("id") String id,@RequestBody AdjustIncomeBody body) {
    return operations.adjustIncome(new AdjustIncome(body.commandId(),id,body.deltaAmount4(),body.reason(),body.expectedVersion(),admin(request)));
  }
  @GetMapping("/api/v1/admin/promotions/benefit-audit")
  public AuditPage audit(HttpServletRequest request,@RequestParam(name="unit",required=false) String unit,
      @RequestParam(name="userId",required=false) String userId,@RequestParam(name="reference",required=false) String reference,
      @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size) {
    admin(request);return operations.audit(unit,userId,reference,page,size);
  }
  @GetMapping("/api/v1/admin/promotions/reconciliation")
  public Reconciliation reconciliation(HttpServletRequest request) {admin(request);return operations.reconciliation();}
  @PostMapping("/api/v1/admin/promotions/reconciliation/preview")
  public Reconciliation preview(HttpServletRequest request,@RequestBody PreviewBody body) {return operations.preview(admin(request),body.reason());}
  private static String identity(HttpServletRequest request) {
    if(request.getUserPrincipal()==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return request.getUserPrincipal().getName();
  }
  private static String admin(HttpServletRequest request) {
    String owner=identity(request);
    if(!request.isUserInRole("ADMIN")||!request.isUserInRole("PRIMARY_ADMIN"))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return owner;
  }
  public record RebindBody(String commandId,String inviterUserId,long expectedVersion,String reason) {}
  public record CreatePayoutBody(String commandId,String withdrawalId,String promoterUserId,String settlementMonth,String amount4,String payoutAccountId,Long expectedDraftVersion) {}
  public record DecideBody(String commandId,boolean approved,String reason,Long expectedAdjustmentVersion,String expectedAmount4) {}
  public record AdjustIncomeBody(String commandId,String deltaAmount4,String reason,long expectedVersion) {}
  public record CompleteBody(String commandId,String transferReference,Instant paidAt) {}
  public record PreviewBody(String reason) {}
}
