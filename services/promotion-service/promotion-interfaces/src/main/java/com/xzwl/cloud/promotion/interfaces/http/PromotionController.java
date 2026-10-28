package com.xzwl.cloud.promotion.interfaces.http;

import com.xzwl.cloud.promotion.application.production.PromotionService;
import com.xzwl.cloud.promotion.application.production.PromotionStore.*;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** New-system-only HTTP API for promoter, commission, referral and withdrawal workflows. */
@RestController
public final class PromotionController {
  private final PromotionService service;
  private com.xzwl.cloud.promotion.application.production.PromotionOperations operations;
  @org.springframework.beans.factory.annotation.Autowired
  public void setOperations(com.xzwl.cloud.promotion.application.production.PromotionOperations operations) { this.operations=operations; }

  public PromotionController(PromotionService service) { this.service = service; }

  @GetMapping("/api/v1/promotions/participation")
  public ParticipationView participation(HttpServletRequest request,@RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size) {
    requireRole(request,"USER"); return service.participation(authenticatedUser(request),page,size);
  }
  @PostMapping("/api/v1/promotions/invitations/bind")
  public ReferralView bindInvitation(HttpServletRequest request,@RequestBody InvitationBody body) {
    requireRole(request,"USER"); return service.bindInvitation(authenticatedUser(request),body.code());
  }
  public record InvitationBody(String code) {}
  @PostMapping("/api/v1/promotions/applications")
  public ApplicationView submit(HttpServletRequest request, @RequestBody ApplicationBody body) {
    requireRole(request,"USER");
    return service.submitApplication(new SubmitApplication(body.commandId(), body.applicationId(),
        authenticatedUser(request), body.reason(), body.plannedChannel(),
        body.agreementsAccepted(), Instant.now()));
  }

  @GetMapping("/api/v1/promotions/applications/{applicationId}")
  public ApplicationView application(HttpServletRequest request,
      @PathVariable("applicationId") String applicationId) {
    ApplicationView result = service.getApplication(applicationId);
    requireOwnerOrAdmin(request, result.userId());
    return result;
  }

  @GetMapping("/api/v1/promotions/promoters/{userId}")
  public PromoterView promoter(HttpServletRequest request, @PathVariable("userId") String userId) {
    requireOwnerOrAdmin(request, userId);
    return service.getPromoter(userId);
  }

  @PostMapping("/api/v1/promotions/referrals/bindings")
  public ReferralView bindReferral(HttpServletRequest request, @RequestBody BindingBody body) {
    String userId = authenticatedUser(request);
    if (body.expectedVersion() != 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return service.bindReferral(new BindReferral(body.commandId(), userId,
        body.directInviterUserId(), body.expectedVersion(), Instant.now()));
  }

  @GetMapping("/api/v1/promotions/referrals/{userId}")
  public ReferralView referral(HttpServletRequest request, @PathVariable("userId") String userId) {
    requireOwnerOrAdmin(request, userId);
    return service.getReferral(userId);
  }

  @GetMapping("/api/v1/promotions/referrals/{userId}/history")
  public Page<ReferralView> referralHistory(HttpServletRequest request,
      @PathVariable("userId") String userId,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    requireOwnerOrAdmin(request, userId);
    return service.listReferralHistory(userId, page, size);
  }

  @GetMapping("/api/v1/promotions/commissions/{userId}")
  public CommissionView commission(HttpServletRequest request, @PathVariable("userId") String userId) {
    requireOwnerOrAdmin(request, userId);
    return service.getCommission(userId);
  }

  @GetMapping("/api/v1/promotions/commissions/{userId}/ledger")
  public Page<CommissionEntryView> commissionLedger(HttpServletRequest request,
      @PathVariable("userId") String userId,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    requireOwnerOrAdmin(request, userId);
    return service.listCommissionEntries(userId, page, size);
  }

  @PostMapping("/api/v1/promotions/withdrawals")
  public WithdrawalView requestWithdrawal(HttpServletRequest request,
      @RequestBody WithdrawalBody body) {
    return service.requestWithdrawal(new RequestWithdrawal(body.commandId(), body.withdrawalId(),
        authenticatedUser(request), body.amount(), body.payoutAccountId(), Instant.now()));
  }

  @GetMapping("/api/v1/promotions/withdrawals/{withdrawalId}")
  public WithdrawalView withdrawal(HttpServletRequest request,
      @PathVariable("withdrawalId") String withdrawalId) {
    WithdrawalView result = service.getWithdrawal(withdrawalId);
    requireOwnerOrAdmin(request, result.promoterUserId());
    return result;
  }

  @PostMapping("/api/v1/internal/promotions/commissions/recharges")
  public CommissionView freeze(HttpServletRequest request, @RequestBody FreezeRecharge body) {
    requireRole(request, "SERVICE"); return service.freezeRecharge(body);
  }

  @PostMapping("/api/v1/internal/promotions/commissions/consumptions")
  public CommissionView settle(HttpServletRequest request, @RequestBody SettleConsumption body) {
    requireRole(request, "SERVICE"); return service.settleConsumption(body);
  }

  @PostMapping("/api/v1/internal/promotions/commissions/refunds")
  public CommissionView refund(HttpServletRequest request, @RequestBody RefundRecharge body) {
    requireRole(request, "SERVICE"); return service.refundRecharge(body);
  }

  @PostMapping("/api/v1/internal/promotions/applicants/{userId}/eligibility")
  public ApplicantEligibilityView eligibility(HttpServletRequest request,
      @PathVariable("userId") String userId, @RequestBody EligibilityBody body) {
    requireRole(request, "SERVICE");
    return service.projectApplicantEligibility(new ProjectApplicantEligibility(
        body.sourceEventId(), userId, body.accountType(), body.accountStatus(), body.registeredAt(),
        body.sourceVersion(), body.occurredAt()));
  }

  @PostMapping("/api/v1/internal/promotions/payout-accounts/{payoutAccountId}")
  public PayoutAccountView payoutAccount(HttpServletRequest request,
      @PathVariable("payoutAccountId") String payoutAccountId, @RequestBody PayoutAccountBody body) {
    requireRole(request, "SERVICE");
    return service.projectPayoutAccount(new ProjectPayoutAccount(body.sourceEventId(),
        payoutAccountId, body.ownerUserId(), body.payoutType(), body.payoutAccountName(),
        body.payoutAccountReference(), body.status(), body.sourceVersion(), body.occurredAt()));
  }

  @GetMapping("/api/v1/admin/promotions/applications")
  public Page<ApplicationView> applications(HttpServletRequest request,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "userId", required = false) String userId,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    requireRole(request, "ADMIN"); return service.listApplications(status, userId, page, size);
  }

  @GetMapping("/api/v1/admin/promotions/applications/{applicationId}")
  public ApplicationView adminApplication(HttpServletRequest request,
      @PathVariable("applicationId") String applicationId) {
    requireRole(request, "ADMIN"); return service.getApplication(applicationId);
  }

  @PostMapping("/api/v1/admin/promotions/applications/{applicationId}/review")
  public ApplicationView review(HttpServletRequest request,
      @PathVariable("applicationId") String applicationId,
      @RequestBody ReviewBody body) {
    requireRole(request, "ADMIN");
    return service.reviewApplication(new ReviewApplication(body.commandId(), applicationId,
        request.getUserPrincipal().getName(), body.decision(), body.rejectionReason(), body.decidedAt()));
  }

  @PatchMapping("/api/v1/admin/promotions/promoters/{userId}/status")
  public PromoterView status(HttpServletRequest request, @PathVariable("userId") String userId,
      @RequestBody StatusBody body) {
    requireRole(request, "ADMIN");
    return service.setPromoterStatus(new ChangePromoterStatus(body.commandId(), userId, body.status(),
        request.getUserPrincipal().getName(), body.changedAt()));
  }

  @GetMapping("/api/v1/admin/promotions/promoters")
  public Page<PromoterView> listPromoters(HttpServletRequest request,
      @RequestParam(name = "userId", required = false) String userId,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    authenticatedUser(request);
    if (!request.isUserInRole("ADMIN") && !request.isUserInRole("NORMAL_ADMIN")) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    return service.listPromoters(userId, page, size);
  }

  private static void requireAdminReader(HttpServletRequest request) {
    authenticatedUser(request);
    if (!request.isUserInRole("ADMIN") && !request.isUserInRole("NORMAL_ADMIN"))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }

  @GetMapping("/api/v1/admin/promotions/promoters/overview")
  public Page<PortalPromotionController.PortalOverviewView> adminPromoterOverviews(HttpServletRequest request,
      @RequestParam(name="userId",required=false) String userId,
      @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size) {
    requireAdminReader(request);
    var result=service.adminPromoterOverviews(userId,page,size);
    return new Page<>(result.items().stream().map(PortalPromotionController::overviewView).toList(),page,size,result.total());
  }
  @GetMapping("/api/v1/admin/promotions/promoters/{userId}/downlines")
  public Page<PortalDownline> adminPromoterDownlines(HttpServletRequest request,@PathVariable("userId") String userId,
      @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size) {
    requireAdminReader(request);return service.adminPromoterDownlines(userId,page,size);
  }
  @GetMapping("/api/v1/admin/promotions/promoters/{userId}/recharges")
  public Page<PortalRecharge> adminPromoterRecharges(HttpServletRequest request,@PathVariable("userId") String userId,
      @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size) {
    requireAdminReader(request);return service.adminPromoterRecharges(userId,page,size);
  }
  @GetMapping("/api/v1/admin/promotions/promoters/{userId}/consumptions")
  public Page<PortalConsumption> adminPromoterConsumptions(HttpServletRequest request,@PathVariable("userId") String userId,
      @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size) {
    requireAdminReader(request);return service.adminPromoterConsumptions(userId,page,size);
  }

  @PostMapping("/api/v1/admin/promotions/promoters")
  public PromoterView createAdminPromoter(HttpServletRequest request, @RequestBody AdminCreatePromoterBody body) {
    requireRole(request, "ADMIN");
    requireRole(request, "PRIMARY_ADMIN");
    return service.createAdminPromoter(new AdminCreatePromoter(body.commandId(), body.userId(),
        request.getUserPrincipal().getName(), Instant.now()));
  }

  @GetMapping("/api/v1/admin/promotions/promoters/{userId}")
  public PromoterView adminPromoter(HttpServletRequest request, @PathVariable("userId") String userId) {
    requireRole(request, "ADMIN"); return service.getPromoter(userId);
  }

  @GetMapping("/api/v1/admin/promotions/referrals/{userId}")
  public ReferralView adminReferral(HttpServletRequest request, @PathVariable("userId") String userId) {
    requireRole(request, "ADMIN"); return service.getReferral(userId);
  }

  @PostMapping("/api/v1/admin/promotions/referrals/{userId}/bindings")
  public ReferralView rebindReferral(HttpServletRequest request,
      @PathVariable("userId") String userId, @RequestBody BindingBody body) {
    requireRole(request, "ADMIN");
    requireRole(request, "PRIMARY_ADMIN");
    if (operations==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    return operations.rebind(new com.xzwl.cloud.promotion.application.production.PromotionOperations.Rebind(
        body.commandId(),userId,body.directInviterUserId(),body.expectedVersion(),body.reason(),authenticatedUser(request))).current();
  }

  @GetMapping("/api/v1/admin/promotions/referrals/{userId}/history")
  public Page<ReferralView> adminReferralHistory(HttpServletRequest request,
      @PathVariable("userId") String userId,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    requireRole(request, "ADMIN");
    return service.listReferralHistory(userId, page, size);
  }

  @GetMapping("/api/v1/admin/promotions/commissions/{userId}")
  public CommissionView adminCommission(HttpServletRequest request, @PathVariable("userId") String userId) {
    requireRole(request, "ADMIN"); return service.getCommission(userId);
  }

  @GetMapping("/api/v1/admin/promotions/commissions")
  public Page<CommissionEntryView> adminCommissionLedger(HttpServletRequest request,
      @RequestParam(name = "promoterUserId", required = false) String promoterUserId,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    requireRole(request, "ADMIN"); return service.listCommissionEntries(promoterUserId, page, size);
  }

  @GetMapping("/api/v1/admin/promotions/withdrawals")
  public Page<WithdrawalView> withdrawals(HttpServletRequest request,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "promoterUserId", required = false) String promoterUserId,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    requireRole(request, "ADMIN");
    return service.listWithdrawals(status, promoterUserId, page, size);
  }

  @GetMapping("/api/v1/admin/promotions/withdrawals/{withdrawalId}")
  public WithdrawalView adminWithdrawal(HttpServletRequest request,
      @PathVariable("withdrawalId") String withdrawalId) {
    requireRole(request, "ADMIN"); return service.getWithdrawal(withdrawalId);
  }

  @PostMapping("/api/v1/admin/promotions/withdrawals/{withdrawalId}/decision")
  public WithdrawalView decide(HttpServletRequest request,
      @PathVariable("withdrawalId") String withdrawalId,
      @RequestBody DecisionBody body) {
    requireRole(request, "ADMIN");
    requireRole(request, "PRIMARY_ADMIN");
    if (operations==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    operations.decidePayout(new com.xzwl.cloud.promotion.application.production.PromotionOperations.DecidePayout(
        body.commandId(),withdrawalId,body.approved(),body.rejectionReason(),authenticatedUser(request),body.expectedAdjustmentVersion(),body.expectedAmount4()));
    return service.getWithdrawal(withdrawalId);
  }

  @PostMapping("/api/v1/admin/promotions/withdrawals/{withdrawalId}/complete")
  public WithdrawalView complete(HttpServletRequest request,
      @PathVariable("withdrawalId") String withdrawalId,
      @RequestBody CompleteBody body) {
    requireRole(request, "ADMIN");
    requireRole(request, "PRIMARY_ADMIN");
    if (operations==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    operations.completePayout(new com.xzwl.cloud.promotion.application.production.PromotionOperations.CompletePayout(
        body.commandId(),withdrawalId,body.transferReference(),body.completedAt(),authenticatedUser(request)));
    return service.getWithdrawal(withdrawalId);
  }

  private static void requireOwner(HttpServletRequest request, String userId) {
    if (!authenticatedUser(request).equals(userId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }
  private static String authenticatedUser(HttpServletRequest request) {
    if (request.getUserPrincipal() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return request.getUserPrincipal().getName();
  }
  private static void requireOwnerOrAdmin(HttpServletRequest request, String userId) {
    if (request.getUserPrincipal() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if (!request.getUserPrincipal().getName().equals(userId) && !request.isUserInRole("ADMIN")) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
  }
  private static void requireRole(HttpServletRequest request, String role) {
    if (request.getUserPrincipal() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if (!request.isUserInRole(role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }

  public record AdminCreatePromoterBody(String commandId, String userId) {}
  public record ReviewBody(String commandId, ApplicationDecision decision, String rejectionReason,
                           Instant decidedAt) {}
  public record ApplicationBody(String commandId, String applicationId, String reason,
                                String plannedChannel, boolean agreementsAccepted) {}
  public record WithdrawalBody(String commandId, String withdrawalId, BigDecimal amount,
                               String payoutAccountId) {}
  public record EligibilityBody(String sourceEventId, String accountType, String accountStatus,
                                Instant registeredAt, long sourceVersion, Instant occurredAt) {}
  public record PayoutAccountBody(String sourceEventId, String ownerUserId, String payoutType,
                                  String payoutAccountName, String payoutAccountReference,
                                  PayoutAccountStatus status, long sourceVersion,
                                  Instant occurredAt) {}
  public record BindingBody(String commandId, String directInviterUserId, long expectedVersion,
                            Instant effectiveAt, String reason) {
    public BindingBody(String commandId,String directInviterUserId,long expectedVersion,Instant effectiveAt) { this(commandId,directInviterUserId,expectedVersion,effectiveAt,null); }
  }
  public record StatusBody(String commandId, PromoterStatus status, Instant changedAt) {}
  public record DecisionBody(String commandId, boolean approved, String rejectionReason,
                             Instant decidedAt, Long expectedAdjustmentVersion, String expectedAmount4) {
    public DecisionBody(String commandId,boolean approved,String rejectionReason,Instant decidedAt) {
      this(commandId,approved,rejectionReason,decidedAt,null,null);
    }
  }
  public record CompleteBody(String commandId, Instant completedAt, String transferReference) {
    public CompleteBody(String commandId,Instant completedAt) { this(commandId,completedAt,null); }
  }
}
