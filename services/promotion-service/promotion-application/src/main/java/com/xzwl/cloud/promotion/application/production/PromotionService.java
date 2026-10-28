package com.xzwl.cloud.promotion.application.production;

import static com.xzwl.cloud.promotion.application.production.PromotionStore.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Validates HTTP-neutral inputs and delegates each use case to one atomic persistence operation. */
public final class PromotionService {
  private static final BigDecimal MINIMUM_WITHDRAWAL = new BigDecimal("100.00");
  private final PromotionStore store;

  public PromotionService(PromotionStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public java.util.List<String> invitationSources(java.util.List<String> owners) { return store.invitationSources(owners); }
  public ParticipationView participation(String owner, int page, int size) { id(owner); page(page,size); return store.participation(owner,page,size); }
  public ReferralView bindInvitation(String owner,String code) { id(owner); text(code); if(!code.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("invalid invitation code"); return store.bindInvitation(owner,code); }
  public ApplicantEligibilityView projectApplicantEligibility(ProjectApplicantEligibility command) {
    Objects.requireNonNull(command, "command");
    ids(command.sourceEventId(), command.userId());
    text(command.accountType(), command.accountStatus());
    if (!"ENABLED".equals(command.accountStatus()) && !"DISABLED".equals(command.accountStatus())) {
      throw new IllegalArgumentException("unsupported applicant account status");
    }
    Objects.requireNonNull(command.registeredAt(), "registeredAt");
    Objects.requireNonNull(command.occurredAt(), "occurredAt");
    if (command.sourceVersion() < 1) throw new IllegalArgumentException("sourceVersion must be positive");
    if (command.registeredAt().isAfter(command.occurredAt())) {
      throw new IllegalArgumentException("registration follows eligibility snapshot");
    }
    return store.projectApplicantEligibility(command);
  }

  public PayoutAccountView projectPayoutAccount(ProjectPayoutAccount command) {
    Objects.requireNonNull(command, "command");
    ids(command.sourceEventId(), command.payoutAccountId(), command.ownerUserId());
    text(command.payoutType(), command.payoutAccountName(), command.payoutAccountReference());
    Objects.requireNonNull(command.status(), "status");
    Objects.requireNonNull(command.occurredAt(), "occurredAt");
    if (command.sourceVersion() < 1) throw new IllegalArgumentException("sourceVersion must be positive");
    return store.projectPayoutAccount(command);
  }

  public ApplicationView submitApplication(SubmitApplication command) {
    Objects.requireNonNull(command, "command");
    ids(command.commandId(), command.applicationId(), command.userId());
    text(command.reason(), command.plannedChannel());
    Objects.requireNonNull(command.submittedAt(), "submittedAt");
    if (!command.agreementsAccepted()) throw new IllegalArgumentException("agreements must be accepted");
    return store.submitApplication(command);
  }

  public ApplicationView reviewApplication(ReviewApplication command) {
    Objects.requireNonNull(command, "command");
    ids(command.commandId(), command.applicationId(), command.adminId());
    Objects.requireNonNull(command.decision(), "decision");
    Objects.requireNonNull(command.decidedAt(), "decidedAt");
    if (command.decision() == ApplicationDecision.REJECT) text(command.rejectionReason());
    return store.reviewApplication(command);
  }

  public PromoterView createAdminPromoter(AdminCreatePromoter command) {
    Objects.requireNonNull(command, "command");
    ids(command.commandId(), command.userId(), command.adminId());
    Objects.requireNonNull(command.occurredAt(), "occurredAt");
    return store.createAdminPromoter(command);
  }

  public Page<PromoterView> listPromoters(String userId, int page, int size) {
    page(page, size);
    String filter = blankToNull(userId);
    return store.listPromoters(filter == null ? null : id(filter), page, size);
  }

  public PromoterView setPromoterStatus(ChangePromoterStatus command) {
    Objects.requireNonNull(command, "command");
    ids(command.commandId(), command.promoterUserId(), command.adminId());
    Objects.requireNonNull(command.status(), "status");
    Objects.requireNonNull(command.changedAt(), "changedAt");
    return store.changePromoterStatus(command);
  }

  public ReferralView bindReferral(BindReferral command) {
    Objects.requireNonNull(command, "command");
    ids(command.commandId(), command.referredUserId(), command.directInviterUserId());
    Objects.requireNonNull(command.effectiveAt(), "effectiveAt");
    if (command.referredUserId().equals(command.directInviterUserId())) {
      throw new IllegalArgumentException("self referral is not allowed");
    }
    if (command.expectedVersion() < 0) throw new IllegalArgumentException("invalid expectedVersion");
    return store.bindReferral(command);
  }

  public BenefitSettings benefitSettings() { return store.benefitSettings(); }
  public java.util.List<BenefitHistory> benefitSettingsHistory() { return store.benefitSettingsHistory(); }
  public BenefitSettings changeBenefitRate(ChangeBenefitRate command) {
    Objects.requireNonNull(command, "command");
    ids(command.commandId(), command.adminId());
    text(command.reason());
    if (command.reason().length() > 2000 || command.expectedVersion() < 1)
      throw new IllegalArgumentException("invalid benefit change");
    boolean global = command.promoterId() == null;
    if (!global) id(command.promoterId());
    var fields = global ? java.util.Set.of("invitationPointsRate", "defaultLevel1CashRate", "defaultLevel2CashRate")
        : java.util.Set.of("level1CashRate", "level2CashRate", "invitationPointsRate");
    if (command.field() == null || !fields.contains(command.field())) throw new IllegalArgumentException("invalid rate field");
    if (command.rate() == null) {
      if (global) throw new IllegalArgumentException("global rate required");
    } else if (!command.rate().matches("(?:0(?:\\.\\d{1,6})?|1(?:\\.0{1,6})?)")) {
      throw new IllegalArgumentException("rate must be 0..1 with at most six decimal places");
    }
    return store.changeBenefitRate(command);
  }

  public String projectPaidRecharge(PaidRecharge command) {
    if (command == null) throw new IllegalArgumentException("paid recharge required");
    ids(command.orderId(), command.userId());
    if (command.orderId().length() > 100) throw new IllegalArgumentException("order id too long");
    positive(command.amount4(), "amount4");
    if (command.amount4().stripTrailingZeros().scale() > 4 || command.amount4().compareTo(BigDecimal.TEN.pow(34)) >= 0)
      throw new IllegalArgumentException("invalid amount precision");
    if (command.createdAt() == null || command.paidAt() == null || command.paidAt().isBefore(command.createdAt())
        || (command.refundedAt() != null && command.refundedAt().isBefore(command.paidAt())))
      throw new IllegalArgumentException("invalid order timestamps");
    return store.projectPaidRecharge(command);
  }

  public CommissionView freezeRecharge(FreezeRecharge command) {
    Objects.requireNonNull(command, "command");
    ids(command.sourceEventId(), command.orderId(), command.fundingLotId(), command.walletOwnerUserId());
    if (command.referralBindingVersion() < 1 || command.benefitPolicyVersion() < 1) {
      throw new IllegalArgumentException("snapshot versions must be positive");
    }
    positive(command.paidAmount(), "paidAmount");
    Objects.requireNonNull(command.occurredAt(), "occurredAt");
    return store.freezeRecharge(command);
  }

  public CommissionView settleConsumption(SettleConsumption command) {
    Objects.requireNonNull(command, "command");
    ids(command.sourceEventId(), command.allocationId(), command.fundingLotId());
    positive(command.successfulPaidAmount(), "successfulPaidAmount");
    Objects.requireNonNull(command.occurredAt(), "occurredAt");
    return store.settleConsumption(command);
  }

  public CommissionView refundRecharge(RefundRecharge command) {
    Objects.requireNonNull(command, "command");
    ids(command.sourceEventId(), command.fundingLotId());
    positive(command.refundedPaidAmount(), "refundedPaidAmount");
    Objects.requireNonNull(command.occurredAt(), "occurredAt");
    return store.refundRecharge(command);
  }

  public WithdrawalView requestWithdrawal(RequestWithdrawal command) {
    Objects.requireNonNull(command, "command");
    ids(command.commandId(), command.withdrawalId(), command.promoterUserId(),
        command.payoutAccountId());
    positive(command.amount(), "amount");
    if (command.amount().scale() > 2) throw new IllegalArgumentException("withdrawal has at most two decimals");
    if (command.amount().compareTo(MINIMUM_WITHDRAWAL) < 0) {
      throw new IllegalArgumentException("withdrawal is below minimum");
    }
    Objects.requireNonNull(command.requestedAt(), "requestedAt");
    return store.requestWithdrawal(command);
  }

  public WithdrawalView decideWithdrawal(DecideWithdrawal command) {
    Objects.requireNonNull(command, "command");
    ids(command.commandId(), command.withdrawalId(), command.adminId());
    Objects.requireNonNull(command.decidedAt(), "decidedAt");
    if (!command.approved()) text(command.rejectionReason());
    return store.decideWithdrawal(command);
  }

  public WithdrawalView completeWithdrawal(CompleteWithdrawal command) {
    Objects.requireNonNull(command, "command");
    ids(command.commandId(), command.withdrawalId(), command.adminId());
    Objects.requireNonNull(command.completedAt(), "completedAt");
    return store.completeWithdrawal(command);
  }

  public ApplicationView getApplication(String id) { return store.getApplication(id(id)); }
  public PromoterView getPromoter(String userId) { return store.getPromoter(id(userId)); }
  public ReferralView getReferral(String userId) { return store.getReferral(id(userId)); }
  public CommissionView getCommission(String userId) { return store.getCommission(id(userId)); }
  public WithdrawalView getWithdrawal(String id) { return store.getWithdrawal(id(id)); }
  public Page<ApplicationView> listApplications(String status, String userId, int page, int size) {
    page(page, size); return store.listApplications(blankToNull(status), blankToNull(userId), page, size);
  }
  public Page<WithdrawalView> listWithdrawals(String status, String userId, int page, int size) {
    page(page, size); return store.listWithdrawals(blankToNull(status), blankToNull(userId), page, size);
  }
  public Page<CommissionEntryView> listCommissionEntries(String userId, int page, int size) {
    page(page, size); return store.listCommissionEntries(blankToNull(userId), page, size);
  }
  public Page<ReferralView> listReferralHistory(String userId, int page, int size) {
    page(page, size); return store.listReferralHistory(id(userId), page, size);
  }

  public Page<PortalOverview> adminPromoterOverviews(String userId, int page, int size) {
    var agents=listPromoters(userId,page,size);
    // ponytail: bounded to the requested page (max 100); batch aggregation if admin traffic grows.
    return new Page<>(agents.items().stream().map(agent -> store.portalOverview(agent.userId())).toList(),page,size,agents.total());
  }
  public Page<PortalDownline> adminPromoterDownlines(String userId,int page,int size) {
    getPromoter(userId);page(page,size);return store.portalDownlines(userId,page,size);
  }
  public Page<PortalRecharge> adminPromoterRecharges(String userId,int page,int size) {
    getPromoter(userId);page(page,size);return store.portalRecharges(userId,page,size);
  }
  public Page<PortalConsumption> adminPromoterConsumptions(String userId,int page,int size) {
    getPromoter(userId);page(page,size);return store.portalConsumptions(userId,page,size);
  }

  public PortalEligibility portalEligibility(String userId) { return store.portalEligibility(id(userId)); }
  public void requirePortalPromoter(String userId) {
    if (!portalEligibility(userId).eligible()) throw new IllegalStateException("promoter is not enabled");
  }
  public PortalOverview portalOverview(String userId) {
    requirePortalPromoter(userId); return store.portalOverview(userId);
  }
  public Page<PortalDownline> portalDownlines(String userId, int page, int size) {
    requirePortalPromoter(userId); page(page, size); return store.portalDownlines(userId, page, size);
  }
  public Page<PortalRecharge> portalRecharges(String userId, int page, int size) {
    requirePortalPromoter(userId); page(page, size); return store.portalRecharges(userId, page, size);
  }
  public Page<PortalConsumption> portalConsumptions(String userId, int page, int size) {
    requirePortalPromoter(userId); page(page, size); return store.portalConsumptions(userId, page, size);
  }
  public FinancialPage<PortalRecharge> portalRecharges(String userId, Instant from, Instant to, int page, int size) {
    requirePortalPromoter(userId); page(page, size); range(from, to);
    return store.portalRecharges(userId, from, to, page, size);
  }
  public FinancialPage<PortalConsumption> portalConsumptions(String userId, Instant from, Instant to, int page, int size) {
    requirePortalPromoter(userId); page(page, size); range(from, to);
    return store.portalConsumptions(userId, from, to, page, size);
  }
  private static void range(Instant from, Instant to) {
    if (from != null && to != null && !from.isBefore(to)) throw new IllegalArgumentException("from must precede to");
  }
  public FinancialPage<PortalPaidMonth> portalPaidMonths(String userId, int page, int size) {
    requirePortalPromoter(userId); page(page, size);
    return store.portalPaidMonths(userId, page, size);
  }
  public FinancialPage<WithdrawalView> portalWithdrawals(String userId, Instant from, Instant to, int page, int size) {
    requirePortalPromoter(userId); page(page, size); range(from, to);
    return store.portalWithdrawals(userId, from, to, page, size);
  }
  public Page<PortalPayoutAccount> portalPayoutAccounts(String userId, int page, int size) {
    requirePortalPromoter(userId); page(page, size); return store.portalPayoutAccounts(userId, page, size);
  }
  public PortalPayoutAccount savePortalPayoutAccount(SavePayoutAccount command) {
    ids(command.commandId(), command.payoutAccountId(), command.ownerUserId());
    Objects.requireNonNull(command.occurredAt(), "occurredAt");
    if (command.expectedVersion() < 0) throw new IllegalArgumentException("invalid expectedVersion");
    switch (Objects.requireNonNull(command.payoutType(), "payoutType")) {
      case "BANK_CARD" -> {
        new com.xzwl.cloud.promotion.domain.payout.BankCardDetails(command.payoutAccountName(), command.payoutAccountReference(), command.bankName());
        if (command.qrCodeReference() != null) throw new IllegalArgumentException("bank card cannot have QR reference");
      }
      case "WECHAT" -> {
        new com.xzwl.cloud.promotion.domain.payout.WechatDetails(command.payoutAccountName(), command.payoutAccountReference(), java.util.Optional.ofNullable(command.qrCodeReference()));
        if (command.bankName() != null) throw new IllegalArgumentException("wechat cannot have bank name");
      }
      case "ALIPAY" -> {
        new com.xzwl.cloud.promotion.domain.payout.AlipayDetails(command.payoutAccountName(), command.payoutAccountReference(), java.util.Optional.ofNullable(command.qrCodeReference()));
        if (command.bankName() != null) throw new IllegalArgumentException("alipay cannot have bank name");
      }
      default -> throw new IllegalArgumentException("unsupported payout type");
    }
    requirePortalPromoter(command.ownerUserId());
    return store.savePortalPayoutAccount(command);
  }

  private static void page(int page, int size) {
    if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("invalid page");
  }
  private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
  private static void positive(BigDecimal value, String name) {
    if (value == null || value.signum() <= 0
        || value.setScale(4, java.math.RoundingMode.DOWN).signum() <= 0
        || value.precision() - value.scale() > 34) {
      throw new IllegalArgumentException(name + " must be a positive DECIMAL(38,4)");
    }
  }
  private static String id(String value) { ids(value); return value; }
  private static void ids(String... values) {
    for (String value : values) if (value == null || value.isBlank() || value.length() > 128) {
      throw new IllegalArgumentException("id must be 1..128 characters");
    }
  }
  private static void text(String... values) {
    for (String value : values) if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("text must not be blank");
    }
  }
}
