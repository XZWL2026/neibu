package com.xzwl.cloud.promotion.interfaces.http;

import com.xzwl.cloud.promotion.application.production.PromotionService;
import com.xzwl.cloud.promotion.application.production.PromotionStore.*;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Owner is always the current PROMOTER session, never a request field. */
@RestController
public final class PortalPromotionController {
  private final PromotionService service;
  private final String internalToken;
  public PortalPromotionController(PromotionService service,
      @Value("${abp.internal-token:${ABP_INTERNAL_TOKEN:}}") String internalToken) {
    this.service = service; this.internalToken = internalToken;
  }

  @GetMapping("/api/v1/internal/promotions/promoters/{userId}/portal-eligibility")
  public PortalEligibility eligibility(HttpServletRequest request, @PathVariable("userId") String userId) {
    String supplied = request.getHeader("X-Service-Token");
    if (internalToken.isBlank() || supplied == null || !MessageDigest.isEqual(
        internalToken.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    return service.portalEligibility(userId);
  }

  @GetMapping("/api/v1/promotions/portal/overview")
  public PortalOverviewView overview(HttpServletRequest request) {
    return overviewView(service.portalOverview(owner(request)));
  }

  static PortalOverviewView overviewView(PortalOverview value) {
    var c = value.commission();
    return new PortalOverviewView(value.promoter(), new PortalCommission(c.promoterUserId(), decimal(c.frozen()), decimal(c.available()),
        decimal(c.withdrawing()), decimal(c.withdrawn()), decimal(c.cashDebt()), c.version(), decimal(c.changedAmount())),
        value.downlineCount(), value.paidAmount4(), value.settledPaidAmount4(), value.refundedPaidAmount4(),
        value.levelOneCount(), value.levelTwoCount(), value.releasedCommissionAmount4(), value.totalCommissionAmount4());
  }

  @GetMapping("/api/v1/promotions/portal/downlines")
  public Page<PortalDownline> downlines(HttpServletRequest request,
      @RequestParam(name="page", defaultValue="0") int page, @RequestParam(name="size", defaultValue="20") int size) {
    return service.portalDownlines(owner(request), page, size);
  }

  @GetMapping("/api/v1/promotions/portal/recharges")
  public FinancialPage<PortalRecharge> recharges(HttpServletRequest request,
      @RequestParam(name="page", defaultValue="0") int page, @RequestParam(name="size", defaultValue="20") int size,
      @RequestParam(name="from", required=false) Instant from, @RequestParam(name="to", required=false) Instant to) {
    return service.portalRecharges(owner(request), from, to, page, size);
  }

  @GetMapping("/api/v1/promotions/portal/consumptions")
  public FinancialPage<PortalConsumption> consumptions(HttpServletRequest request,
      @RequestParam(name="page", defaultValue="0") int page, @RequestParam(name="size", defaultValue="20") int size,
      @RequestParam(name="from", required=false) Instant from, @RequestParam(name="to", required=false) Instant to) {
    return service.portalConsumptions(owner(request), from, to, page, size);
  }

  @GetMapping("/api/v1/promotions/portal/paid-months")
  public FinancialPage<PortalPaidMonth> paidMonths(HttpServletRequest request,
      @RequestParam(name="page", defaultValue="0") int page, @RequestParam(name="size", defaultValue="20") int size) {
    return service.portalPaidMonths(owner(request), page, size);
  }

  @GetMapping("/api/v1/promotions/portal/withdrawals")
  public FinancialPage<PortalWithdrawalView> withdrawals(HttpServletRequest request,
      @RequestParam(name="page", defaultValue="0") int page, @RequestParam(name="size", defaultValue="20") int size,
      @RequestParam(name="from", required=false) Instant from, @RequestParam(name="to", required=false) Instant to) {
    var result = service.portalWithdrawals(owner(request), from, to, page, size);
    return new FinancialPage<>(result.items().stream().map(PortalPromotionController::maskedWithdrawal).toList(), page, size, result.total(), result.totalAmount4());
  }

  @PostMapping("/api/v1/promotions/portal/withdrawals")
  public PortalWithdrawalView withdraw(HttpServletRequest request, @RequestBody PortalWithdrawalBody body) {
    if (body.amount() == null || !body.amount().matches("[0-9]{1,34}(\\.[0-9]{1,2})?")) throw new IllegalArgumentException("invalid withdrawal amount");
    return maskedWithdrawal(service.requestWithdrawal(new RequestWithdrawal(body.commandId(), body.withdrawalId(), owner(request),
        new BigDecimal(body.amount()), body.payoutAccountId(), Instant.now())));
  }

  @GetMapping("/api/v1/promotions/portal/payout-accounts")
  public Page<PortalPayoutAccount> payouts(HttpServletRequest request,
      @RequestParam(name="page", defaultValue="0") int page, @RequestParam(name="size", defaultValue="20") int size) {
    return service.portalPayoutAccounts(owner(request), page, size);
  }

  @PutMapping("/api/v1/promotions/portal/payout-accounts/{payoutAccountId}")
  public PortalPayoutAccount savePayout(HttpServletRequest request, @PathVariable("payoutAccountId") String payoutAccountId,
      @RequestBody PortalPayoutBody body) {
    return service.savePortalPayoutAccount(new SavePayoutAccount(body.commandId(), payoutAccountId, owner(request), body.payoutType(),
        body.payoutAccountName(), body.payoutAccountReference(), body.bankName(), body.qrCodeReference(), body.expectedVersion(), Instant.now()));
  }

  private String owner(HttpServletRequest request) {
    if (request.getUserPrincipal() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if (!request.isUserInRole("PROMOTER")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    String owner = request.getUserPrincipal().getName();
    if (!service.portalEligibility(owner).eligible()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return owner;
  }
  private static PortalWithdrawalView maskedWithdrawal(WithdrawalView value) {
    return new PortalWithdrawalView(value.id(), value.promoterUserId(), decimal(value.amount()), value.status(), value.payoutAccountId(),
        value.payoutAccountSourceVersion(), value.payoutAccountSnapshotAt(), value.payoutType(), "****", value.maskedPayoutAccountNo(),
        value.rejectionReason(), value.requestedAt(), value.updatedAt());
  }
  private static String decimal(BigDecimal value) { return value == null ? null : value.setScale(4).toPlainString(); }
  public record PortalCommission(String promoterUserId, String frozen, String available, String withdrawing,
      String withdrawn, String cashDebt, long version, String changedAmount) {}
  public record PortalOverviewView(PromoterView promoter, PortalCommission commission, long downlineCount,
      String paidAmount4, String settledPaidAmount4, String refundedPaidAmount4,
      long levelOneCount, long levelTwoCount, String releasedCommissionAmount4, String totalCommissionAmount4) {}
  public record PortalWithdrawalView(String id, String promoterUserId, String amount, WithdrawalStatus status,
      String payoutAccountId, long payoutAccountSourceVersion, Instant payoutAccountSnapshotAt,
      String payoutType, String payoutAccountName, String maskedPayoutAccountNo,
      String rejectionReason, Instant requestedAt, Instant updatedAt) {}
  public record PortalWithdrawalBody(String commandId, String withdrawalId, String amount, String payoutAccountId) {}
  public record PortalPayoutBody(String commandId, String payoutType, String payoutAccountName,
      String payoutAccountReference, String bankName, String qrCodeReference, long expectedVersion) {}
}
