package com.xzwl.cloud.promotion.infrastructure;

import com.xzwl.cloud.promotion.application.production.PromotionStore;
import com.xzwl.cloud.promotion.domain.referral.BenefitSnapshotResolver;
import com.xzwl.cloud.promotion.domain.referral.DirectInviterFacts;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xzwl.cloud.promotion.infrastructure.persistence.PromotionRows.*;
import com.xzwl.cloud.promotion.infrastructure.persistence.PromotionMappers.*;
import com.xzwl.cloud.promotion.infrastructure.persistence.PromotionSqlMapper;
import java.util.function.Function;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** MyBatis-Plus persistence; every mutating method retains one database transaction. */
public final class MyBatisPromotionStore implements PromotionStore {
  public List<String> invitationSources(List<String> owners) {
    if(owners==null||owners.isEmpty()||owners.size()>100||new HashSet<>(owners).size()!=owners.size()
        ||owners.stream().anyMatch(id->id==null||!id.matches("[A-Za-z0-9._:-]{1,128}")))throw new IllegalArgumentException("invalid directory batch");
    return referrals.selectList(new QueryWrapper<ReferralBindingRow>().in("referred_user_id",owners)
        .eq("version",1)
        .apply("NOT EXISTS (SELECT 1 FROM promotion_admin_operation a WHERE a.command_id=referral_binding.command_id AND a.kind='REFERRAL')")).stream().map(row->row.referredUserId).distinct().toList();
  }
  private static final BigDecimal ZERO = new BigDecimal("0.0000");
  private static final java.security.SecureRandom INVITATION_RANDOM = new java.security.SecureRandom();
  private static final DateTimeFormatter INSTANT_TEXT =
      new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
  private final Eligibilities eligibilities;
  private final Applications applications;
  private final Promoters promoters;
  private final Receipts receipts;
  private final Referrals referrals;
  private final Policies policies;
  private final Accounts accounts;
  private final FundingBenefits fundingBenefits;
  private final Ledger ledger;
  private final Payouts payouts;
  private final Withdrawals withdrawals;
  private final PromotionSqlMapper sql;
  private final TransactionTemplate transaction;
  private java.util.function.Consumer<PaidRecharge> paidRechargeProjection = command -> {};
  public void setPaidRechargeProjection(java.util.function.Consumer<PaidRecharge> projection) { paidRechargeProjection=Objects.requireNonNull(projection); }
  public LiveApplicant applicantForOperations(String owner) { return liveApplicant(owner); }
  private java.time.Clock benefitClock = java.time.Clock.systemUTC();
  public void setBenefitClock(java.time.Clock clock) { benefitClock = Objects.requireNonNull(clock); }
  private Function<String, Boolean> adminUserDirectory = userId -> {
    throw new IllegalStateException("user directory is not configured");
  };
  private Function<String, LiveApplicant> liveApplicants;
  public void setLiveApplicants(Function<String, LiveApplicant> directory) { liveApplicants=Objects.requireNonNull(directory); }
  private LiveApplicant liveApplicant(String owner) {
    if(liveApplicants==null)throw new IllegalStateException("applicant directory unavailable");
    var facts=liveApplicants.apply(owner);
    if(facts==null||!owner.equals(facts.userId()))throw new IllegalStateException("applicant identity mismatch");
    return facts;
  }
  private boolean eligible(LiveApplicant facts) { return facts.enabled()&&(Set.of("PERSONAL","ENTERPRISE","API").contains(facts.accountType())||enabledPromoter(facts.userId())); }
  private String ensureInvitation(String owner) {
    Long number=sql.invitationNumber(owner);
    if(number==null) {
      var promoter=promoters.selectById(owner);
      String legacyCode=promoter==null?invitationCode(owner):promoter.invitationCode;
      for(int attempt=0;attempt<32 && number==null;attempt++) {
        sql.ensureInvitation(owner,legacyCode,1+INVITATION_RANDOM.nextInt(1_679_615));
        number=sql.lockInvitation(owner);
      }
      if(number==null) throw new IllegalStateException("could not allocate a unique invitation code after 32 attempts");
    }
    // ponytail: four base-36 characters support 1,679,615 codes; widen if bounded retries become frequent.
    if(number==null || number<1 || number>=1_679_616L) throw new IllegalStateException("four-character invitation capacity exhausted");
    return String.format("%4s",Long.toString(number,36).toUpperCase(java.util.Locale.ROOT)).replace(' ','0');
  }
  public ParticipationView participation(String owner,int page,int size) {
    var facts=liveApplicant(owner);
    if(!facts.enabled())throw new IllegalStateException("user is disabled");
    boolean allowed=eligible(facts);
    String code=allowed?transaction.execute(s->ensureInvitation(owner)):null;
    var latest=listApplications(null,owner,0,1).items();
    var promoter=promoters.selectById(owner);
    var invites=sql.directInvites(owner,(long)page*size,size).stream().map(row->{
      var user=liveApplicant(row.referredUserId);
      return new DirectInvite(user.nickname(),user.maskedPhone(),user.registeredAt());
    }).toList();
    return new ParticipationView(facts.accountType(),allowed,code,latest.isEmpty()?null:latest.getFirst(),promoter==null?null:mapPromoter(promoter),new Page<>(invites,page,size,sql.directInviteCount(owner)));
  }
  public ReferralView bindInvitation(String owner,String code) {
    var user=liveApplicant(owner);
    if(!user.enabled())throw new IllegalStateException("user is disabled");
    return idempotentTransaction(()->{
      sql.lockReferralGraph();
      ensureInvitation(owner);
      String inviter=sql.invitationOwner(code);
      if(inviter==null && code.matches("[A-Z0-9]{4}")) inviter=sql.numberedInvitationOwner(Long.parseLong(code,36));
      if(inviter==null||inviter.equals(owner))throw new IllegalArgumentException("invalid invitation");
      if(!eligible(liveApplicant(inviter)))throw new IllegalStateException("inviter is not eligible");
      var current=currentReferral(owner,true);
      if(!current.isEmpty()){
        if(!current.getFirst().directInviterUserId().equals(inviter))throw new IllegalStateException("invitation is already bound");
        return current.getFirst();
      }
      return bindReferral(new BindReferral("invite-"+UUID.randomUUID(),owner,inviter,0,Instant.now()));
    });
  }
  private Function<String, PortalDownlineFacts> portalDirectory = userId -> null;
  private Function<PortalAllocationLookup, PortalAllocationFacts> portalAllocations = lookup -> null;

  public MyBatisPromotionStore(Eligibilities eligibilities,
      Applications applications,
      Promoters promoters,
      Receipts receipts,
      Referrals referrals,
      Policies policies,
      Accounts accounts,
      FundingBenefits fundingBenefits,
      Ledger ledger,
      Payouts payouts,
      Withdrawals withdrawals,
      PromotionSqlMapper sql, PlatformTransactionManager manager) {
    this.eligibilities = eligibilities;
    this.applications = applications;
    this.promoters = promoters;
    this.receipts = receipts;
    this.referrals = referrals;
    this.policies = policies;
    this.accounts = accounts;
    this.fundingBenefits = fundingBenefits;
    this.ledger = ledger;
    this.payouts = payouts;
    this.withdrawals = withdrawals;
    this.sql = sql;
    transaction = new TransactionTemplate(manager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public void setAdminUserDirectory(Function<String, Boolean> directory) {
    adminUserDirectory = Objects.requireNonNull(directory);
  }

  public void setPortalAllocationDirectory(Function<PortalAllocationLookup, PortalAllocationFacts> directory) {
    portalAllocations = Objects.requireNonNull(directory);
  }

  public void setPortalDirectory(Function<String, PortalDownlineFacts> directory) {
    portalDirectory = Objects.requireNonNull(directory);
  }

  @Override
  public ApplicantEligibilityView projectApplicantEligibility(ProjectApplicantEligibility command) {
    String semantics = fields(command.userId(), command.accountType(), command.accountStatus(),
        command.registeredAt(), command.sourceVersion(), command.occurredAt());
    return idempotentTransaction(() -> {
      if (replayed(command.sourceEventId(), "APPLICANT_ELIGIBILITY", command.userId(), semantics)) {
        return applicantEligibility(command.userId(), false);
      }
      List<ApplicantEligibilityView> current = applicantEligibilityRows(command.userId(), true);
      if (current.isEmpty()) {
        eligibilities.insert(new ApplicantEligibilityRow(command.userId(), command.accountType(), command.accountStatus(),
            timestamp(command.registeredAt()), command.sourceVersion(), command.sourceEventId(),
            timestamp(command.occurredAt())));
      } else {
        ApplicantEligibilityView existing = current.getFirst();
        if (command.sourceVersion() < existing.sourceVersion()) {
          throw new IllegalStateException("applicant eligibility source version is stale");
        }
        if (command.sourceVersion() == existing.sourceVersion()) {
          if (!same(existing, command)) {
            throw new IllegalStateException("applicant eligibility version has different semantics");
          }
        } else {
          sql.updateEligibility(command.accountType(), command.accountStatus(), timestamp(command.registeredAt()),
              command.sourceVersion(), command.sourceEventId(), timestamp(command.occurredAt()),
              command.userId());
        }
      }
      record(command.sourceEventId(), "APPLICANT_ELIGIBILITY", command.userId(), semantics,
          command.occurredAt());
      return applicantEligibility(command.userId(), false);
    });
  }

  @Override
  public PayoutAccountView projectPayoutAccount(ProjectPayoutAccount command) {
    String semantics = fields(command.payoutAccountId(), command.ownerUserId(), command.payoutType(),
        command.payoutAccountName(), command.payoutAccountReference(), command.status(),
        command.sourceVersion(), command.occurredAt());
    return idempotentTransaction(() -> {
      if (replayed(command.sourceEventId(), "PAYOUT_ACCOUNT", command.payoutAccountId(), semantics)) {
        return payoutAccount(command.payoutAccountId(), false).view();
      }
      List<PayoutAccount> current = payoutAccountRows(command.payoutAccountId(), true);
      if (current.isEmpty()) {
        payouts.insert(new PayoutAccountRow(command.payoutAccountId(), command.ownerUserId(), command.payoutType(),
            command.payoutAccountName(), command.payoutAccountReference(), command.status().name(),
            command.sourceVersion(), command.sourceEventId(), timestamp(command.occurredAt())));
      } else {
        PayoutAccount existing = current.getFirst();
        if (command.sourceVersion() < existing.sourceVersion) {
          throw new IllegalStateException("payout account source version is stale");
        }
        if (command.sourceVersion() == existing.sourceVersion) {
          if (!same(existing, command)) {
            throw new IllegalStateException("payout account version has different semantics");
          }
        } else {
          sql.updatePayoutAccount(command.ownerUserId(), command.payoutType(), command.payoutAccountName(),
              command.payoutAccountReference(), command.status().name(), command.sourceVersion(),
              command.sourceEventId(), timestamp(command.occurredAt()), command.payoutAccountId());
        }
      }
      record(command.sourceEventId(), "PAYOUT_ACCOUNT", command.payoutAccountId(), semantics,
          command.occurredAt());
      return payoutAccount(command.payoutAccountId(), false).view();
    });
  }

  @Override
  public ApplicationView submitApplication(SubmitApplication command) {
    String semantics = fields(command.applicationId(), command.userId(), command.reason(),
        command.plannedChannel(), command.agreementsAccepted());
    return idempotentTransaction(() -> {
      if (replayed(command.commandId(), "APPLICATION_SUBMIT", command.applicationId(), semantics)) {
        return getApplication(command.applicationId());
      }
      ApplicantEligibilityView eligibility;
      if(liveApplicants!=null){
        ensureInvitation(command.userId());
        var facts=liveApplicant(command.userId());
        if(!facts.enabled())throw new IllegalStateException("applicant is not eligible");
        eligibility=new ApplicantEligibilityView(facts.userId(),facts.accountType(),"ENABLED",facts.registeredAt(),facts.version(),command.submittedAt());
      }else eligibility = applicantEligibility(command.userId(), true);
      if (replayed(command.commandId(), "APPLICATION_SUBMIT", command.applicationId(), semantics)) {
        return getApplication(command.applicationId());
      }
      if(promoters.selectById(command.userId())!=null || applications.selectCount(new QueryWrapper<ApplicationRow>().eq("user_id",command.userId()).eq("status","PENDING"))>0)throw new IllegalStateException("application is already pending or approved");
      if (!"ENABLED".equals(eligibility.accountStatus())) {
        throw new IllegalStateException("applicant is not eligible");
      }
      if (eligibility.updatedAt().isAfter(command.submittedAt())) {
        throw new IllegalStateException("eligibility snapshot postdates application submission");
      }
      sql.submitApplication(command.applicationId(), command.commandId(), command.userId(),
          eligibility.accountType(), eligibility.accountStatus(), timestamp(eligibility.registeredAt()),
          eligibility.sourceVersion(), timestamp(eligibility.updatedAt()), command.reason(),
          command.plannedChannel(), command.agreementsAccepted(), timestamp(command.submittedAt()));
      record(command.commandId(), "APPLICATION_SUBMIT", command.applicationId(), semantics,
          command.submittedAt());
      return getApplication(command.applicationId());
    });
  }

  @Override
  public ApplicationView reviewApplication(ReviewApplication command) {
    String semantics = fields(command.applicationId(), command.adminId(), command.decision(),
        command.rejectionReason(), command.decidedAt());
    return idempotentTransaction(() -> {
      if (replayed(command.commandId(), "APPLICATION_REVIEW", command.applicationId(), semantics)) {
        return getApplication(command.applicationId());
      }
      ApplicationView current = lockApplication(command.applicationId());
      if (replayed(command.commandId(), "APPLICATION_REVIEW", command.applicationId(), semantics)) {
        return current;
      }
      if (!"PENDING".equals(current.status())) throw new IllegalStateException("application is already decided");
      if (command.decidedAt().isBefore(current.submittedAt())) {
        throw new IllegalArgumentException("decision precedes submission");
      }
      String status = command.decision() == ApplicationDecision.APPROVE ? "APPROVED" : "REJECTED";
      sql.reviewApplication(status, command.adminId(), timestamp(command.decidedAt()), command.rejectionReason(),
          command.applicationId());
      if (command.decision() == ApplicationDecision.APPROVE) {
        sql.createPromoter(current.userId(), ensureInvitation(current.userId()), timestamp(command.decidedAt()),
            timestamp(command.decidedAt()));
        sql.createCommissionAccount(current.userId());
      }
      record(command.commandId(), "APPLICATION_REVIEW", command.applicationId(), semantics,
          command.decidedAt());
      return getApplication(command.applicationId());
    });
  }

  @Override
  public PromoterView createAdminPromoter(AdminCreatePromoter command) {
    String semantics = fields(command.userId(), command.adminId());
    return idempotentTransaction(() -> {
      boolean replay = replayed(command.commandId(), "ADMIN_PROMOTER_CREATE", command.userId(), semantics);
      if (!replay && !Boolean.TRUE.equals(adminUserDirectory.apply(command.userId()))) {
        throw new IllegalStateException("user is not enabled");
      }
      var existing = sql.lockPromoter(command.userId());
      if (existing.isEmpty()) {
        sql.createPromoter(command.userId(), ensureInvitation(command.userId()),
            timestamp(command.occurredAt()), timestamp(command.occurredAt()));
        sql.createCommissionAccount(command.userId());
      } else if (mapPromoter(existing.getFirst()).status() != PromoterStatus.ENABLED) {
        throw new IllegalStateException("promoter is disabled");
      }
      if (!replay) record(command.commandId(), "ADMIN_PROMOTER_CREATE", command.userId(), semantics, command.occurredAt());
      return getPromoter(command.userId());
    });
  }

  private static String invitationCode(String userId) {
    return "P" + UUID.nameUUIDFromBytes(userId.getBytes(StandardCharsets.UTF_8))
        .toString().replace("-", "").substring(0, 15).toUpperCase(java.util.Locale.ROOT);
  }

  @Override
  public Page<PromoterView> listPromoters(String userId, int page, int size) {
    return page(promoters, new QueryWrapper<PromoterRow>()
        .eq(userId != null, "promoter_user_id", userId)
        .orderByDesc("created_at").orderByAsc("promoter_user_id"), page, size, this::mapPromoter);
  }

  @Override
  public PromoterView changePromoterStatus(ChangePromoterStatus command) {
    String semantics = fields(command.promoterUserId(), command.status(), command.adminId(),
        command.changedAt());
    return idempotentTransaction(() -> {
      if (replayed(command.commandId(), "PROMOTER_STATUS", command.promoterUserId(), semantics)) {
        return getPromoter(command.promoterUserId());
      }
      PromoterView current = lockPromoter(command.promoterUserId());
      if (replayed(command.commandId(), "PROMOTER_STATUS", command.promoterUserId(), semantics)) {
        return current;
      }
      if (command.changedAt().isBefore(current.updatedAt())) {
        throw new IllegalArgumentException("changedAt precedes current state");
      }
      sql.changePromoterStatus(command.status().name(), timestamp(command.changedAt()), command.promoterUserId());
      record(command.commandId(), "PROMOTER_STATUS", command.promoterUserId(), semantics,
          command.changedAt());
      return getPromoter(command.promoterUserId());
    });
  }

  @Override
  public ReferralView bindReferral(BindReferral command) {
    String semantics = fields(command.referredUserId(), command.directInviterUserId(),
        command.expectedVersion(), command.effectiveAt());
    return idempotentTransaction(() -> {
      if (replayed(command.commandId(), "REFERRAL_BIND", command.referredUserId(), semantics)) {
        return getReferral(command.referredUserId());
      }
      // ponytail: serialize relation writes to preserve cycle checks; partition the graph only if write throughput requires it.
      sql.lockReferralGraph();
      long version = currentReferralVersionForUpdate(command.referredUserId());
      if (replayed(command.commandId(), "REFERRAL_BIND", command.referredUserId(), semantics)) {
        return getReferral(command.referredUserId());
      }
      if (version != command.expectedVersion()) throw new IllegalStateException("referral version conflict");
      rejectCycle(command.referredUserId(), command.directInviterUserId());

      var benefit = BenefitSnapshotResolver.resolve(new DirectInviterFacts(
          command.directInviterUserId(), enabledPromoter(command.directInviterUserId()), null));
      long next = version + 1;
      sql.insertReferralBinding(command.referredUserId(), next, command.commandId(), command.directInviterUserId(),
          benefit.cashPromoterUserId(), null, benefit.pointBeneficiaryUserId(), benefit.cashPromoterUserId(),
          benefit.cashTier().name(), timestamp(command.effectiveAt()));
      record(command.commandId(), "REFERRAL_BIND", command.referredUserId(), semantics,
          command.effectiveAt());
      return getReferral(command.referredUserId());
    });
  }

  @Override
  public BenefitSettings benefitSettings() { return settings(latestPolicy()); }

  private BenefitPolicyRow latestPolicy() {
    return policies.selectList(new QueryWrapper<BenefitPolicyRow>().orderByDesc("version").last("LIMIT 1")).getFirst();
  }

  private static String rateText(BigDecimal rate) { return rate == null ? null : rate.setScale(6).toPlainString(); }

  private String ruleEffectiveAt(long version, String scope, String owner, String field) {
    var changes=policies.selectList(new QueryWrapper<BenefitPolicyRow>().le("version",version)
        .eq("change_scope",scope).eq("changed_promoter_id",owner)
        .and(q->q.eq("changed_field",field).or().isNull("changed_field")).orderByDesc("version"));
    for(var change:changes) {
      String changed=change.changedField==null?changedField(change,policies.selectById(change.version-1)):change.changedField;
      if(field.equals(changed)) return change.effectiveAt;
    }
    return null;
  }

  private BenefitSettings settings(BenefitPolicyRow policy) {
    var overrides = policies.overrides(policy.version);
    var agents = promoters.selectList(new QueryWrapper<PromoterRow>().orderByAsc("promoter_user_id")).stream().map(agent -> {
      var override = overrides.stream().filter(o -> o.promoterUserId.equals(agent.promoterUserId)).findFirst().orElse(null);
      var user = liveApplicants == null ? null : liveApplicant(agent.promoterUserId);
      return new BenefitPromoter(agent.promoterUserId, user == null ? agent.promoterUserId : user.nickname(),
          override == null ? null : rateText(override.level1CashRate6), override == null ? null : rateText(override.level2CashRate6),
          user == null ? null : user.maskedPhone(), override == null ? null : ruleEffectiveAt(policy.version,"PROMOTER_OVERRIDE",agent.promoterUserId,"level1CashRate"),
          user != null && user.enabled() && "ENABLED".equals(agent.status));
    }).toList();
    var customers=policies.customerOverrides(policy.version).stream().map(override -> {
      var user=liveApplicant(override.customerUserId);
      return new BenefitCustomer(user.userId(),user.nickname(),user.maskedPhone(),rateText(override.pointRate6),
          ruleEffectiveAt(policy.version,"CUSTOMER_OVERRIDE",user.userId(),"invitationPointsRate"),user.enabled(),user.accountType(),enabledPromoter(user.userId()));
    }).toList();
    return new BenefitSettings(policy.version, policy.effectiveAt, policy.operatorId, policy.reason,
        new BenefitGlobal(rateText(policy.pointRate6),rateText(policy.level1CashRate6),rateText(policy.level2CashRate6)), agents, customers);
  }

  @Override
  public BenefitSettings changeBenefitRate(ChangeBenefitRate raw) {
    String semantics = fields(raw.expectedVersion(),raw.promoterId(),raw.field(),raw.rate(),raw.reason(),raw.adminId());
    var saved = idempotentTransaction(() -> {
      // ponytail: settings writes serialize on the permanent first policy row; admin traffic is low.
      policies.selectList(new QueryWrapper<BenefitPolicyRow>().eq("version",1).last("FOR UPDATE"));
      if (replayed(raw.commandId(),"BENEFIT_RATE","benefit-policy",semantics))
        return policies.selectOne(new QueryWrapper<BenefitPolicyRow>().eq("command_id",raw.commandId()));
      var previous = latestPolicy();
      if (previous.version != raw.expectedVersion()) throw new IllegalStateException("benefit settings version changed; reload before saving");
      boolean customer=raw.promoterId()!=null && "invitationPointsRate".equals(raw.field());
      if (customer) {
        if (raw.rate()==null) {
          if (policies.customerOverrides(previous.version).stream().noneMatch(o->o.customerUserId.equals(raw.promoterId())))
            throw new IllegalArgumentException("customer rule not found");
        } else {
          var user=liveApplicant(raw.promoterId());
          if (!user.enabled() || !Set.of("PERSONAL","ENTERPRISE","API").contains(user.accountType()) || enabledPromoter(user.userId()))
            throw new IllegalArgumentException("customer is not eligible for point rules");
        }
      } else if (raw.promoterId() != null && promoters.selectById(raw.promoterId()) == null)
        throw new IllegalArgumentException("promoter not found");
      var next = new BenefitPolicyRow();
      next.version=previous.version+1;
      next.pointRate6=previous.pointRate6;next.level1CashRate6=previous.level1CashRate6;next.level2CashRate6=previous.level2CashRate6;
      Instant savedAt=benefitClock.instant();
      Instant time = savedAt.isAfter(Instant.parse(previous.effectiveAt)) ? savedAt : Instant.parse(previous.effectiveAt).plusNanos(1);
      next.effectiveAt=timestamp(time);next.operatorId=raw.adminId();next.reason=raw.reason();next.commandId=raw.commandId();
      next.changeScope=raw.promoterId()==null ? "GLOBAL" : customer ? "CUSTOMER_OVERRIDE" : "PROMOTER_OVERRIDE";
      next.changedPromoterId=raw.promoterId();next.changedField=raw.field();
      BigDecimal rate = raw.rate()==null ? null : new BigDecimal(com.xzwl.cloud.promotion.domain.money.Rate6.parse(raw.rate()).toString());
      if (raw.promoterId()==null) switch(raw.field()) {
        case "invitationPointsRate" -> next.pointRate6=rate;
        case "defaultLevel1CashRate" -> next.level1CashRate6=rate;
        case "defaultLevel2CashRate" -> next.level2CashRate6=rate;
        default -> throw new IllegalArgumentException("invalid global rate");
      }
      policies.insert(next);
      policies.copyOverrides(previous.version,next.version);
      policies.copyCustomerOverrides(previous.version,next.version);
      if (customer) {
        policies.deleteCustomerOverride(next.version,raw.promoterId());
        if(rate!=null) policies.insertCustomerOverride(next.version,raw.promoterId(),rate);
      } else if (raw.promoterId()!=null) {
        var old=policies.overrides(previous.version).stream().filter(o->o.promoterUserId.equals(raw.promoterId())).findFirst().orElse(new BenefitOverrideRow());
        if ("level1CashRate".equals(raw.field())) old.level1CashRate6=rate; else old.level2CashRate6=rate;
        policies.deleteOverride(next.version,raw.promoterId());
        if(old.level1CashRate6!=null || old.level2CashRate6!=null)
          policies.insertOverride(next.version,raw.promoterId(),old.level1CashRate6,old.level2CashRate6);
      }
      record(raw.commandId(),"BENEFIT_RATE","benefit-policy",semantics,time);
      return next;
    });
    return settings(saved);
  }

  private String configuredRate(BenefitPolicyRow policy, String scope, String target, String field) {
    if(policy==null || field==null) return null;
    if("CUSTOMER_OVERRIDE".equals(scope)) return policies.customerOverrides(policy.version).stream()
        .filter(o->o.customerUserId.equals(target)).map(o->rateText(o.pointRate6)).findFirst().orElse(null);
    if("GLOBAL".equals(scope)) return switch(field) {
      case "invitationPointsRate" -> rateText(policy.pointRate6);
      case "defaultLevel1CashRate" -> rateText(policy.level1CashRate6);
      case "defaultLevel2CashRate" -> rateText(policy.level2CashRate6);
      default -> null;
    };
    var override=policies.overrides(policy.version).stream().filter(o->o.promoterUserId.equals(target)).findFirst().orElse(null);
    return override==null?null:rateText("level1CashRate".equals(field)?override.level1CashRate6:override.level2CashRate6);
  }

  private String changedField(BenefitPolicyRow policy, BenefitPolicyRow previous) {
    boolean global="GLOBAL".equals(policy.changeScope);
    String field=policy.changedField;
    if(field==null && previous!=null) {
      var candidates=global ? List.of("invitationPointsRate","defaultLevel1CashRate","defaultLevel2CashRate") : "CUSTOMER_OVERRIDE".equals(policy.changeScope)?List.of("invitationPointsRate"):List.of("level1CashRate","level2CashRate");
      var receipt=policy.commandId==null?null:receipts.selectById(policy.commandId);
      if(receipt!=null && "BENEFIT_RATE".equals(receipt.commandType))
        field=candidates.stream().filter(f->receipt.semantics.startsWith(fields(previous.version,policy.changedPromoterId,f))).findFirst().orElse(null);
      if(field==null) {
        var changed=candidates.stream().filter(f->!Objects.equals(configuredRate(previous,policy.changeScope,policy.changedPromoterId,f),
            configuredRate(policy,policy.changeScope,policy.changedPromoterId,f))).toList();
        if(changed.size()==1) field=changed.getFirst();
      }
    }
    return field;
  }

  @Override
  public List<BenefitHistory> benefitSettingsHistory() {
    var history=policies.selectList(new QueryWrapper<BenefitPolicyRow>().orderByDesc("version"));
    return history.stream().map(policy -> {
      boolean global="GLOBAL".equals(policy.changeScope);
      var override=!"PROMOTER_OVERRIDE".equals(policy.changeScope) ? null : policies.overrides(policy.version).stream().filter(o->o.promoterUserId.equals(policy.changedPromoterId)).findFirst().orElse(null);
      var previous=history.stream().filter(p->p.version<policy.version).findFirst().orElse(null);
      String field=changedField(policy,previous);
      return new BenefitHistory(policy.version,policy.changeScope,policy.changedPromoterId,
          global?rateText(policy.pointRate6):"CUSTOMER_OVERRIDE".equals(policy.changeScope)?configuredRate(policy,policy.changeScope,policy.changedPromoterId,"invitationPointsRate"):null,
          global?rateText(policy.level1CashRate6):null,global?rateText(policy.level2CashRate6):null,
          override==null?null:rateText(override.level1CashRate6),override==null?null:rateText(override.level2CashRate6),
          policy.effectiveAt,policy.operatorId,policy.reason,field,
          configuredRate(previous,policy.changeScope,policy.changedPromoterId,field),configuredRate(policy,policy.changeScope,policy.changedPromoterId,field));
    }).toList();
  }

  @Override
  public String projectPaidRecharge(PaidRecharge raw) {
    String receipt = "paid-credit:" + raw.orderId();
    String lot = "recharge:" + raw.orderId();
    String semantics = fields(raw.userId(), money(raw.amount4()), raw.createdAt(), raw.paidAt());
    return idempotentTransaction(() -> {
      if (!replayed(receipt, "PAID_RECHARGE", raw.orderId(), semantics)) {
        boolean existing = fundingBenefits.selectCount(new QueryWrapper<FundingBenefitRow>()
            .eq("funding_lot_id", lot).or().eq("order_id", raw.orderId())) > 0;
        if (existing) {
          // A concurrent delivery may have committed since the first read; old unowned funding is a conflict.
          if (!replayed(receipt, "PAID_RECHARGE", raw.orderId(), semantics))
            throw new IllegalStateException("existing funding requires its original delivery receipt");
        } else {
          var bindings = referrals.selectList(new QueryWrapper<ReferralBindingRow>()
              .eq("referred_user_id", raw.userId()).le("effective_at", timestamp(raw.createdAt()))
              .orderByDesc("effective_at", "version").last("LIMIT 1"));
          if (!bindings.isEmpty() && "LEVEL_1".equals(bindings.getFirst().cashTier)
              && bindings.getFirst().cashPromoterUserId != null) {
            var applicable = policies.selectList(new QueryWrapper<BenefitPolicyRow>()
                .le("effective_at",timestamp(raw.createdAt())).orderByDesc("effective_at").last("LIMIT 1"));
            if(applicable.isEmpty()) throw new IllegalStateException("historical benefit policy not found");
            var policy=applicable.getFirst();
            freezeRecharge(new FreezeRecharge("paid-freeze:" + raw.orderId(), raw.orderId(), lot, raw.userId(),
                bindings.getFirst().version, policy.version, raw.amount4(), raw.paidAt()));
          }
          // Persist NONE as well: a later binding must not turn this order into a new commission.
          record(receipt, "PAID_RECHARGE", raw.orderId(), semantics, raw.paidAt());
        }
      }
      paidRechargeProjection.accept(raw);
      if (fundingBenefits.selectById(lot) == null) return "NONE";
      if (raw.refundedAt() != null) refundRecharge(new RefundRecharge(
          "paid-refund:" + raw.orderId(), lot, raw.amount4(), raw.refundedAt()));
      return "RESOLVED";
    });
  }

  @Override
  public CommissionView freezeRecharge(FreezeRecharge raw) {
    BigDecimal paid = money(raw.paidAmount());
    String semantics = fields(raw.orderId(), raw.fundingLotId(), raw.walletOwnerUserId(),
        raw.referralBindingVersion(), raw.benefitPolicyVersion(), paid, raw.occurredAt());
    return idempotentTransaction(() -> {
      if (replayed(raw.sourceEventId(), "RECHARGE_FREEZE", raw.fundingLotId(), semantics)) {
        List<CommissionEntryView> replay = ledgerByEvent(raw.sourceEventId());
        Funding funding = fundingByEvent(raw.sourceEventId());
        return commission(funding.promoterUserId, replay.getFirst().amount());
      }
      ReferralRow referral = referralRow(raw.walletOwnerUserId(), raw.referralBindingVersion());
      // Historical funded orders replay above; only direct agent invitations may create new cash.
      if (referral.cashPromoterUserId == null || !"LEVEL_1".equals(referral.cashTier))
        throw new IllegalStateException("recharge has no direct cash beneficiary");
      BenefitPolicyRow policy = policies.selectById(raw.benefitPolicyVersion());
      if (policy == null) throw new org.springframework.dao.EmptyResultDataAccessException(1);
      BigDecimal rate = policy.level1CashRate6;
      var override = policies.overrides(policy.version).stream().filter(o->o.promoterUserId.equals(referral.cashPromoterUserId)).findFirst().orElse(null);
      if(override!=null) {
        var individual=override.level1CashRate6;
        if(individual!=null) rate=individual;
      }
      if (rate == null) throw new IllegalStateException("benefit policy snapshot not found");
      BigDecimal amount = paid.multiply(Objects.requireNonNull(rate)).setScale(4, RoundingMode.DOWN);
      Account account = lockAccount(referral.cashPromoterUserId);
      if (replayed(raw.sourceEventId(), "RECHARGE_FREEZE", raw.fundingLotId(), semantics)) {
        CommissionEntryView replay = ledgerByEvent(raw.sourceEventId()).getFirst();
        return commission(replay.promoterUserId(), replay.amount());
      }
      long sequence = account.version + 1;
      sql.freezeAccount(account.frozen.add(amount), sequence, account.userId, account.version);
      sql.createFundingBenefit(raw.fundingLotId(), raw.sourceEventId(), raw.orderId(), raw.walletOwnerUserId(),
          referral.version, raw.benefitPolicyVersion(), referral.cashPromoterUserId,
          referral.cashTier, rate, paid, amount,
          timestamp(raw.occurredAt()));
      insertLedger(account.userId, sequence, "COMMISSION_FROZEN", amount, raw.sourceEventId(),
          raw.orderId(), raw.fundingLotId(), null, null, raw.occurredAt());
      record(raw.sourceEventId(), "RECHARGE_FREEZE", raw.fundingLotId(), semantics,
          raw.occurredAt());
      return commission(account.userId, amount);
    });
  }

  @Override
  public CommissionView settleConsumption(SettleConsumption raw) {
    BigDecimal successful = money(raw.successfulPaidAmount());
    String semantics = fields(raw.allocationId(), raw.fundingLotId(), successful,
        raw.finalDepletion(), raw.occurredAt());
    return idempotentTransaction(() -> {
      if (replayed(raw.sourceEventId(), "CONSUMPTION_SETTLE", raw.fundingLotId(), semantics)) {
        CommissionEntryView replay = ledgerByEvent(raw.sourceEventId()).getFirst();
        return commission(replay.promoterUserId(), replay.amount());
      }
      Funding funding = lockFunding(raw.fundingLotId());
      if (ledger.selectCount(new QueryWrapper<CommissionLedgerRow>().eq("funding_lot_id", raw.fundingLotId())
          .apply("EXISTS (SELECT 1 FROM promotion_consumption_receipt r WHERE r.event_id = promotion_commission_ledger.source_event_id)")) > 0)
        throw new IllegalStateException("funding uses durable Billing settlement; legacy settlement rejected");
      if (replayed(raw.sourceEventId(), "CONSUMPTION_SETTLE", raw.fundingLotId(), semantics)) {
        CommissionEntryView replay = ledgerByEvent(raw.sourceEventId()).getFirst();
        return commission(replay.promoterUserId(), replay.amount());
      }
      if (ledger.selectCount(new QueryWrapper<CommissionLedgerRow>()
          .eq("allocation_id", raw.allocationId())) > 0) {
        throw new IllegalStateException("allocation already has a commission release");
      }
      BigDecimal remainingPaid = funding.paidAmount.subtract(funding.settledPaidAmount)
          .subtract(funding.refundedPaidAmount);
      if (successful.compareTo(remainingPaid) > 0) throw new IllegalStateException("settlement exceeds lot remainder");
      BigDecimal remainingCommission = funding.frozenCommission
          .subtract(funding.releasedCommission).subtract(funding.clawedUnreleasedCommission);
      BigDecimal release = raw.finalDepletion() ? remainingCommission
          : funding.frozenCommission.multiply(successful).divide(funding.paidAmount, 4, RoundingMode.DOWN);
      if (release.signum() < 0 || release.compareTo(remainingCommission) > 0) {
        throw new IllegalStateException("invalid commission release");
      }
      Account account = lockAccount(funding.promoterUserId);
      if (release.compareTo(account.frozen) > 0) throw new IllegalStateException("frozen balance is insufficient");
      BigDecimal debtOffset = release.min(account.cashDebt);
      long sequence = account.version + 1;
      sql.releaseAccount(account.frozen.subtract(release),
          account.available.add(release.subtract(debtOffset)), account.cashDebt.subtract(debtOffset),
          sequence,
          account.userId, account.version);
      sql.settleFundingBenefit(funding.releasedCommission.add(release), funding.settledPaidAmount.add(successful),
          funding.fundingLotId);
      insertLedger(account.userId, sequence, "COMMISSION_RELEASED", release, raw.sourceEventId(),
          funding.orderId, funding.fundingLotId, raw.allocationId(), null, raw.occurredAt());
      sql.recordSuccessfulPaidAmount(raw.sourceEventId(), successful);
      record(raw.sourceEventId(), "CONSUMPTION_SETTLE", raw.fundingLotId(), semantics,
          raw.occurredAt());
      return commission(account.userId, release);
    });
  }

  @Override
  public CommissionView refundRecharge(RefundRecharge raw) {
    BigDecimal refund = money(raw.refundedPaidAmount());
    String semantics = fields(raw.fundingLotId(), refund, raw.occurredAt());
    return idempotentTransaction(() -> {
      if (replayed(raw.sourceEventId(), "RECHARGE_REFUND", raw.fundingLotId(), semantics)) {
        CommissionEntryView replay = ledgerByEvent(raw.sourceEventId()).getFirst();
        return commission(replay.promoterUserId(), replay.amount());
      }
      Funding funding = lockFunding(raw.fundingLotId());
      if (replayed(raw.sourceEventId(), "RECHARGE_REFUND", raw.fundingLotId(), semantics)) {
        CommissionEntryView replay = ledgerByEvent(raw.sourceEventId()).getFirst();
        return commission(replay.promoterUserId(), replay.amount());
      }
      BigDecimal refundable = funding.paidAmount.subtract(funding.refundedPaidAmount);
      if (refund.compareTo(refundable) > 0) throw new IllegalStateException("refund exceeds unsettled lot remainder");
      BigDecimal refundedPaid = funding.refundedPaidAmount.add(refund);
      BigDecimal target = refundedPaid.compareTo(funding.paidAmount) == 0
          ? funding.frozenCommission : proportional(funding, refundedPaid);
      BigDecimal alreadyReversed = funding.clawedUnreleasedCommission
          .add(funding.reversedReleasedCommission);
      BigDecimal clawback = target.subtract(alreadyReversed);
      if (clawback.signum() < 0) throw new IllegalStateException("refund commission is zero");
      BigDecimal lotFrozen = funding.frozenCommission.subtract(funding.releasedCommission)
          .subtract(funding.clawedUnreleasedCommission);
      BigDecimal fromFrozen = clawback.min(lotFrozen);
      BigDecimal fromReleased = clawback.subtract(fromFrozen);
      BigDecimal releasedOutstanding = funding.releasedCommission
          .subtract(funding.reversedReleasedCommission);
      if (fromReleased.compareTo(releasedOutstanding) > 0) {
        throw new IllegalStateException("refund exceeds commission outstanding");
      }
      Account account = lockAccount(funding.promoterUserId);
      if (fromFrozen.compareTo(account.frozen) > 0) {
        throw new IllegalStateException("frozen balance is insufficient");
      }
      BigDecimal debitedAvailable = fromReleased.min(account.available);
      BigDecimal debtCreated = fromReleased.subtract(debitedAvailable);
      long sequence = account.version + 1;
      sql.releaseAccount(account.frozen.subtract(fromFrozen), account.available.subtract(debitedAvailable),
          account.cashDebt.add(debtCreated), sequence, account.userId, account.version);
      sql.refundFundingBenefit(refundedPaid, funding.clawedUnreleasedCommission.add(fromFrozen),
          funding.reversedReleasedCommission.add(fromReleased), funding.fundingLotId);
      insertLedger(account.userId, sequence, "RECHARGE_REFUND_CLAWBACK", clawback,
          raw.sourceEventId(), funding.orderId, funding.fundingLotId, null, null, raw.occurredAt());
      record(raw.sourceEventId(), "RECHARGE_REFUND", raw.fundingLotId(), semantics,
          raw.occurredAt());
      return commission(account.userId, clawback);
    });
  }

  @Override
  public WithdrawalView requestWithdrawal(RequestWithdrawal raw) {
    BigDecimal amount = money(raw.amount());
    String semantics = fields(raw.withdrawalId(), raw.promoterUserId(), amount,
        raw.payoutAccountId());
    return idempotentTransaction(() -> {
      if (replayedWithdrawal(raw, semantics)) {
        return getWithdrawal(raw.withdrawalId());
      }
      PromoterView promoter = lockPromoter(raw.promoterUserId());
      if (replayedWithdrawal(raw, semantics)) {
        return getWithdrawal(raw.withdrawalId());
      }
      if (promoter.status() != PromoterStatus.ENABLED) throw new IllegalStateException("promoter is disabled");
      PayoutAccount payout = payoutAccount(raw.payoutAccountId(), true);
      if (!payout.ownerUserId.equals(raw.promoterUserId())) {
        throw new IllegalStateException("payout account belongs to another user");
      }
      if (payout.status != PayoutAccountStatus.ACTIVE) {
        throw new IllegalStateException("payout account is not active");
      }
      if (payout.updatedAt.isAfter(raw.requestedAt())) {
        throw new IllegalStateException("payout account snapshot postdates withdrawal request");
      }
      Account account = lockAccount(raw.promoterUserId());
      if (account.cashDebt.signum() > 0) throw new IllegalStateException("cash debt must be cleared");
      if (amount.compareTo(account.available) > 0) throw new IllegalStateException("withdrawal exceeds available balance");
      long sequence = account.version + 1;
      sql.reserveWithdrawal(account.available.subtract(amount), account.withdrawing.add(amount), sequence,
          account.userId, account.version);
      sql.requestWithdrawal(raw.withdrawalId(), raw.commandId(), raw.promoterUserId(), amount,
          payout.id, payout.sourceVersion, timestamp(payout.updatedAt), payout.payoutType,
          payout.accountName, payout.accountReference, payout.bankName, payout.qrCodeReference, timestamp(raw.requestedAt()),
          timestamp(raw.requestedAt()));
      insertLedger(account.userId, sequence, "WITHDRAWAL_REQUESTED", amount, raw.commandId(),
          null, null, null, raw.withdrawalId(), raw.requestedAt());
      record(raw.commandId(), "WITHDRAWAL_REQUEST", raw.withdrawalId(), semantics, raw.requestedAt());
      return getWithdrawal(raw.withdrawalId());
    });
  }

  @Override
  public WithdrawalView decideWithdrawal(DecideWithdrawal command) {
    String action = command.approved() ? "APPROVE" : "REJECT";
    String semantics = fields(command.withdrawalId(), command.adminId(), action,
        command.rejectionReason(), command.decidedAt());
    return idempotentTransaction(() -> {
      if (replayed(command.commandId(), "WITHDRAWAL_" + action, command.withdrawalId(), semantics)) {
        return getWithdrawal(command.withdrawalId());
      }
      WithdrawalView current = lockWithdrawal(command.withdrawalId());
      if (replayed(command.commandId(), "WITHDRAWAL_" + action, command.withdrawalId(), semantics)) {
        return current;
      }
      boolean compensation = !command.approved()
          && current.status() == WithdrawalStatus.PENDING_PAYOUT;
      if ((command.approved() && current.status() != WithdrawalStatus.WITHDRAWING)
          || (!command.approved() && current.status() != WithdrawalStatus.WITHDRAWING
              && !compensation)) {
        throw new IllegalStateException("withdrawal is not awaiting review");
      }
      if (command.decidedAt().isBefore(current.updatedAt())) throw new IllegalArgumentException("decision precedes state");
      WithdrawalStatus target = command.approved() ? WithdrawalStatus.PENDING_PAYOUT : WithdrawalStatus.REJECTED;
      if (!command.approved()) {
        Account account = lockAccount(current.promoterUserId());
        if (current.amount().compareTo(account.withdrawing) > 0) throw new IllegalStateException("reserved balance is insufficient");
        long sequence = account.version + 1;
        BigDecimal debtOffset = current.amount().min(account.cashDebt);
        sql.returnWithdrawal(account.available.add(current.amount().subtract(debtOffset)),
            account.withdrawing.subtract(current.amount()), account.cashDebt.subtract(debtOffset),
            sequence, account.userId, account.version);
        insertLedger(account.userId, sequence,
            compensation ? "WITHDRAWAL_COMPENSATED" : "WITHDRAWAL_REJECTED", current.amount(),
            command.commandId(), null, null, null, current.id(), command.decidedAt());
      }
      sql.decideWithdrawal(target.name(), command.rejectionReason(), timestamp(command.decidedAt()), current.id(),
          current.status().name());
      insertTransition(command.commandId(), current.id(), compensation ? "COMPENSATE" : action,
          command.adminId(),
          command.rejectionReason(), command.decidedAt(), target);
      record(command.commandId(), "WITHDRAWAL_" + action, current.id(), semantics, command.decidedAt());
      return getWithdrawal(current.id());
    });
  }

  @Override
  public WithdrawalView completeWithdrawal(CompleteWithdrawal command) {
    String semantics = fields(command.withdrawalId(), command.adminId(), command.completedAt());
    return idempotentTransaction(() -> {
      if (replayed(command.commandId(), "WITHDRAWAL_COMPLETE", command.withdrawalId(), semantics)) {
        return getWithdrawal(command.withdrawalId());
      }
      WithdrawalView current = lockWithdrawal(command.withdrawalId());
      if (replayed(command.commandId(), "WITHDRAWAL_COMPLETE", command.withdrawalId(), semantics)) {
        return current;
      }
      if (current.status() != WithdrawalStatus.PENDING_PAYOUT) {
        throw new IllegalStateException("withdrawal is not awaiting payout");
      }
      if (command.completedAt().isBefore(current.updatedAt())) throw new IllegalArgumentException("completion precedes state");
      Account account = lockAccount(current.promoterUserId());
      if (account.cashDebt.signum() > 0) {
        throw new IllegalStateException("cash debt must be cleared before payout");
      }
      if (current.amount().compareTo(account.withdrawing) > 0) throw new IllegalStateException("reserved balance is insufficient");
      long sequence = account.version + 1;
      sql.payWithdrawal(account.withdrawing.subtract(current.amount()), account.withdrawn.add(current.amount()),
          sequence, account.userId, account.version);
      sql.completeWithdrawal(timestamp(command.completedAt()), current.id());
      insertLedger(account.userId, sequence, "WITHDRAWAL_PAID", current.amount(), command.commandId(),
          null, null, null, current.id(), command.completedAt());
      insertTransition(command.commandId(), current.id(), "COMPLETE", command.adminId(), null,
          command.completedAt(), WithdrawalStatus.PAID);
      record(command.commandId(), "WITHDRAWAL_COMPLETE", current.id(), semantics,
          command.completedAt());
      return getWithdrawal(current.id());
    });
  }

  @Override
  public ApplicationView getApplication(String applicationId) {
    return applications.selectList(new QueryWrapper<ApplicationRow>().eq("application_id", applicationId))
        .stream().map(MyBatisPromotionStore::mapApplication).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("application not found"));
  }

  @Override
  public PromoterView getPromoter(String userId) {
    return promoters.selectList(new QueryWrapper<PromoterRow>().eq("promoter_user_id", userId))
        .stream().map(this::mapPromoter).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("promoter not found"));
  }

  @Override
  public ReferralView getReferral(String userId) {
    List<ReferralView> rows = currentReferral(userId, false);
    if (rows.isEmpty()) throw new IllegalArgumentException("referral not found");
    return rows.getFirst();
  }

  @Override
  public CommissionView getCommission(String promoterUserId) {
    return commission(promoterUserId, ZERO);
  }

  @Override
  public WithdrawalView getWithdrawal(String withdrawalId) {
    return withdrawals.selectList(new QueryWrapper<WithdrawalRow>().eq("withdrawal_id", withdrawalId))
        .stream().map(MyBatisPromotionStore::mapWithdrawal).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("withdrawal not found"));
  }

  @Override
  public Page<ApplicationView> listApplications(String status, String userId, int page, int size) {
    return page(applications, new QueryWrapper<ApplicationRow>()
        .eq(status != null, "status", status).eq(userId != null, "user_id", userId)
        .orderByDesc("submitted_at").orderByAsc("application_id"),
        page, size, MyBatisPromotionStore::mapApplication);
  }

  @Override
  public Page<WithdrawalView> listWithdrawals(String status, String promoterUserId, int page, int size) {
    return page(withdrawals, new QueryWrapper<WithdrawalRow>()
        .eq(status != null, "status", status)
        .eq(promoterUserId != null, "promoter_user_id", promoterUserId)
        .orderByDesc("requested_at").orderByAsc("withdrawal_id"),
        page, size, MyBatisPromotionStore::mapWithdrawal);
  }

  @Override
  public FinancialPage<PortalPaidMonth> portalPaidMonths(String userId, int page, int size) {
    var months = new java.util.TreeMap<String, BigDecimal>(java.util.Comparator.reverseOrder());
    // ponytail: scan this owner's paid ledger only; move month aggregation to SQL if payout histories become large.
    var paid = ledger.selectList(new QueryWrapper<CommissionLedgerRow>().eq("promoter_user_id", userId)
        .eq("action_type", "WITHDRAWAL_PAID").select("created_at", "amount4"));
    for (var entry : paid) {
      var month = java.time.YearMonth.from(Instant.parse(entry.createdAt).atZone(java.time.ZoneId.of("Asia/Shanghai"))).toString();
      months.merge(month, entry.amount4, BigDecimal::add);
    }
    var items = months.entrySet().stream().skip((long) page * size).limit(size)
        .map(entry -> new PortalPaidMonth(entry.getKey(), money(entry.getValue()).toPlainString())).toList();
    var total = months.values().stream().reduce(ZERO, BigDecimal::add);
    return new FinancialPage<>(items, page, size, months.size(), money(total).toPlainString());
  }

  @Override
  public FinancialPage<WithdrawalView> portalWithdrawals(String userId, Instant from, Instant to, int page, int size) {
    var query = new QueryWrapper<WithdrawalRow>().eq("promoter_user_id", userId);
    if (from != null) query.ge("requested_at", timestamp(from));
    if (to != null) query.lt("requested_at", timestamp(to));
    return financialPage(withdrawals, query, "amount4", page, size, MyBatisPromotionStore::mapWithdrawal, "requested_at", "withdrawal_id", false);
  }

  @Override
  public Page<CommissionEntryView> listCommissionEntries(String promoterUserId, int page, int size) {
    return page(ledger, new QueryWrapper<CommissionLedgerRow>()
        .eq(promoterUserId != null, "promoter_user_id", promoterUserId)
        .orderByDesc("created_at").orderByAsc("promoter_user_id").orderByDesc("sequence_no"),
        page, size, MyBatisPromotionStore::mapLedger);
  }

  @Override
  public Page<ReferralView> listReferralHistory(String referredUserId, int page, int size) {
    return page(referrals, new QueryWrapper<ReferralBindingRow>()
        .eq("referred_user_id", referredUserId).orderByDesc("version"),
        page, size, MyBatisPromotionStore::mapReferral);
  }

  @Override
  public PortalEligibility portalEligibility(String userId) {
    return new PortalEligibility(userId, enabledPromoter(userId));
  }

  private QueryWrapper<ReferralBindingRow> downlineQuery(String userId) {
    return new QueryWrapper<ReferralBindingRow>()
        .apply("version = (SELECT MAX(current_binding.version) FROM referral_binding current_binding WHERE current_binding.referred_user_id = referral_binding.referred_user_id)")
        .eq("direct_inviter_user_id", userId);
  }

  private QueryWrapper<FundingBenefitRow> portalFundingQuery(String userId) {
    return new QueryWrapper<FundingBenefitRow>().eq("promoter_user_id", userId)
        .apply("EXISTS (SELECT 1 FROM referral_binding current_binding WHERE current_binding.referred_user_id = promotion_funding_benefit.wallet_owner_user_id AND current_binding.version = (SELECT MAX(latest.version) FROM referral_binding latest WHERE latest.referred_user_id = current_binding.referred_user_id) AND current_binding.direct_inviter_user_id = {0})", userId);
  }

  @Override
  public PortalOverview portalOverview(String userId) {
    var sums = fundingBenefits.selectMaps(portalFundingQuery(userId).select(
        "COALESCE(SUM(paid_amount4),0) AS paid", "COALESCE(SUM(settled_paid_amount4),0) AS settled",
        "COALESCE(SUM(refunded_paid_amount4),0) AS refunded")).getFirst();
    // Financial ownership survives later referral changes. Withdrawal movements are not new earnings.
    var earned = ledger.selectMaps(new QueryWrapper<CommissionLedgerRow>().eq("promoter_user_id", userId).select(
        "COALESCE(SUM(CASE WHEN action_type = 'COMMISSION_RELEASED' THEN amount4 WHEN action_type = 'CONSUMPTION_RELEASE_REVERSED' THEN -amount4 ELSE 0 END),0) AS released",
        "COALESCE(SUM(CASE WHEN action_type = 'COMMISSION_FROZEN' THEN amount4 WHEN action_type IN ('RECHARGE_REFUND_CLAWBACK','COMMISSION_RATE_CORRECTION') THEN -amount4 ELSE 0 END),0) AS earned")).getFirst();
    // Production refund ledger entries combine frozen and released clawback; funding retains the exact released split.
    var reversal = fundingBenefits.selectMaps(new QueryWrapper<FundingBenefitRow>().eq("promoter_user_id", userId)
        .select("COALESCE(SUM(reversed_released_commission4),0) AS reversed")).getFirst();
    long invited = referrals.selectCount(downlineQuery(userId));
    return new PortalOverview(getPromoter(userId), getCommission(userId), invited,
        money(new BigDecimal(sums.get("paid").toString())).toPlainString(), money(new BigDecimal(sums.get("settled").toString())).toPlainString(),
        money(new BigDecimal(sums.get("refunded").toString())).toPlainString(), invited, 0,
        money(new BigDecimal(earned.get("released").toString()).subtract(new BigDecimal(reversal.get("reversed").toString()))).toPlainString(),
        money(new BigDecimal(earned.get("earned").toString())).toPlainString());
  }

  @Override
  public Page<PortalDownline> portalDownlines(String userId, int page, int size) {
    return page(referrals, downlineQuery(userId).orderByDesc("effective_at").orderByAsc("referred_user_id"), page, size, row -> {
      ApplicantEligibilityRow identity = eligibilities.selectById(row.referredUserId);
      PortalDownlineFacts facts = portalDirectory.apply(row.referredUserId);
      return new PortalDownline(mask(row.referredUserId), facts == null ? null : facts.nickname(), facts == null ? null : facts.maskedPhone(),
          identity == null ? null : identity.accountType, facts != null ? (facts.enabled() ? "ENABLED" : "DISABLED") : identity == null ? null : identity.accountStatus,
          facts != null ? facts.registeredAt() : identity == null ? null : instant(identity.registeredAt), 1,
          instant(row.effectiveAt), null, facts == null ? null : facts.totalRechargeAmount4(), facts == null ? null : facts.consumedPoints4());
    });
  }

  @Override
  public Page<PortalRecharge> portalRecharges(String userId, int page, int size) {
    var result = portalRecharges(userId, null, null, page, size);
    return new Page<>(result.items(), page, size, result.total());
  }

  @Override
  public FinancialPage<PortalRecharge> portalRecharges(String userId, Instant from, Instant to, int page, int size) {
    var query = portalFundingQuery(userId);
    if (from != null) query.ge("occurred_at", timestamp(from));
    if (to != null) query.lt("occurred_at", timestamp(to));
    return financialPage(fundingBenefits, query, "paid_amount4", page, size, row -> {
          PortalDownlineFacts identity = portalDirectory.apply(row.walletOwnerUserId);
          return new PortalRecharge(row.orderId, mask(row.walletOwnerUserId), row.cashTier,
              row.paidAmount4.toPlainString(), row.refundedPaidAmount4.toPlainString(), row.frozenCommission4.toPlainString(), instant(row.occurredAt),
              identity == null ? null : identity.nickname(), identity == null ? null : identity.maskedPhone());
        }, "occurred_at", "order_id", false);
  }

  @Override
  public Page<PortalConsumption> portalConsumptions(String userId, int page, int size) {
    var result = portalConsumptions(userId, null, null, page, size);
    return new Page<>(result.items(), page, size, result.total());
  }

  @Override
  public FinancialPage<PortalConsumption> portalConsumptions(String userId, Instant from, Instant to, int page, int size) {
    String allocationLedger = " FROM promotion_commission_ledger net WHERE net.promoter_user_id = promotion_commission_ledger.promoter_user_id"
        + " AND net.funding_lot_id = promotion_commission_ledger.funding_lot_id AND net.allocation_id = promotion_commission_ledger.allocation_id"
        + " AND net.action_type IN ('COMMISSION_RELEASED','CONSUMPTION_RELEASE_REVERSED')";
    String netPaid = "(SELECT CASE WHEN COUNT(*) = COUNT(net.successful_paid_amount4) THEN SUM(net.successful_paid_amount4) ELSE NULL END" + allocationLedger + ")";
    String netCommission = "(SELECT SUM(CASE WHEN net.action_type = 'CONSUMPTION_RELEASE_REVERSED' THEN -net.amount4 ELSE net.amount4 END)" + allocationLedger + ")";
    // Keep one original consumption row and its date; later refunds change its net values.
    var query = new QueryWrapper<CommissionLedgerRow>().eq("promoter_user_id", userId)
        .eq("action_type", "COMMISSION_RELEASED")
        .apply("NOT EXISTS (SELECT 1 FROM promotion_commission_ledger earlier WHERE earlier.promoter_user_id = promotion_commission_ledger.promoter_user_id AND earlier.funding_lot_id = promotion_commission_ledger.funding_lot_id AND earlier.allocation_id = promotion_commission_ledger.allocation_id AND earlier.action_type = 'COMMISSION_RELEASED' AND earlier.sequence_no < promotion_commission_ledger.sequence_no)")
        .apply("EXISTS (SELECT 1 FROM promotion_funding_benefit f JOIN referral_binding r ON r.referred_user_id = f.wallet_owner_user_id WHERE f.funding_lot_id = promotion_commission_ledger.funding_lot_id AND r.version = (SELECT MAX(latest.version) FROM referral_binding latest WHERE latest.referred_user_id = r.referred_user_id) AND r.direct_inviter_user_id = {0})", userId);
    if (from != null) query.ge("created_at", timestamp(from));
    if (to != null) query.lt("created_at", timestamp(to));
    query.select("promoter_user_id", "sequence_no", "funding_lot_id", "allocation_id", "created_at",
        netPaid + " AS successful_paid_amount4", netCommission + " AS amount4");
    return financialPage(ledger, query, netPaid, page, size, row -> {
      FundingBenefitRow funding = fundingBenefits.selectById(row.fundingLotId);
      PortalDownlineFacts identity = portalDirectory.apply(funding.walletOwnerUserId);
      PortalAllocationFacts allocation = portalAllocations.apply(new PortalAllocationLookup(funding.walletOwnerUserId, row.allocationId, row.fundingLotId));
      return new PortalConsumption(row.allocationId, mask(funding.walletOwnerUserId), funding.cashTier,
          allocation == null ? null : allocation.type(), row.amount4.toPlainString(), row.successfulPaidAmount4 == null ? null : row.successfulPaidAmount4.toPlainString(), instant(row.createdAt),
          identity == null ? null : identity.nickname(), identity == null ? null : identity.maskedPhone(),
          allocation == null ? null : allocation.points4(), allocation == null ? null : allocation.balanceBeforePoints4(), allocation == null ? null : allocation.balanceAfterPoints4(),
          allocation == null ? null : allocation.balanceBeforeAmount4(), allocation == null ? null : allocation.balanceAfterAmount4());
    }, "created_at", "sequence_no", true);
  }

  @Override
  public Page<PortalPayoutAccount> portalPayoutAccounts(String userId, int page, int size) {
    return page(payouts, new QueryWrapper<PayoutAccountRow>().eq("owner_user_id", userId)
        .orderByDesc("updated_at").orderByAsc("payout_account_id"), page, size, MyBatisPromotionStore::portalPayout);
  }

  @Override
  public PortalPayoutAccount savePortalPayoutAccount(SavePayoutAccount command) {
    String semantics = fields(command.payoutAccountId(), command.ownerUserId(), command.payoutType(),
        command.payoutAccountName(), command.payoutAccountReference(), command.bankName(),
        command.qrCodeReference(), command.expectedVersion());
    return idempotentTransaction(() -> {
      // Serialize the owner's writes with promoter status changes and withdrawal requests.
      if (lockPromoter(command.ownerUserId()).status() != PromoterStatus.ENABLED) {
        throw new IllegalStateException("promoter is disabled");
      }
      if (replayed(command.commandId(), "PORTAL_PAYOUT_SAVE", command.payoutAccountId(), semantics)) {
        return portalPayout(payouts.selectById(command.payoutAccountId()));
      }
      var existing = payouts.selectById(command.payoutAccountId());
      if (existing != null && !existing.ownerUserId.equals(command.ownerUserId())) {
        throw new IllegalStateException("payout account belongs to another user");
      }
      if ((existing == null ? 0 : existing.sourceVersion) != command.expectedVersion()) {
        throw new IllegalStateException("payout account version conflict");
      }
      var row = new PayoutAccountRow(command.payoutAccountId(), command.ownerUserId(), command.payoutType(),
          command.payoutAccountName(), command.payoutAccountReference(), existing == null ? "ACTIVE" : existing.status, command.expectedVersion() + 1,
          command.commandId(), timestamp(command.occurredAt()));
      row.bankName = command.bankName();
      row.qrCodeReference = command.qrCodeReference() == null && existing != null && existing.payoutType.equals(command.payoutType())
          ? existing.qrCodeReference : command.qrCodeReference();
      if (existing == null) payouts.insert(row);
      else {
        var update = new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PayoutAccountRow>()
            .eq("payout_account_id", command.payoutAccountId()).eq("owner_user_id", command.ownerUserId())
            .eq("source_version", command.expectedVersion())
            .set("payout_type", row.payoutType).set("payout_account_name", row.payoutAccountName)
            .set("payout_account_reference", row.payoutAccountReference).set("bank_name", row.bankName)
            .set("qr_code_reference", row.qrCodeReference).set("status", row.status)
            .set("source_version", row.sourceVersion).set("last_source_event_id", row.lastSourceEventId)
            .set("updated_at", row.updatedAt);
        if (payouts.update(null, update) != 1) throw new IllegalStateException("payout account version conflict");
      }
      record(command.commandId(), "PORTAL_PAYOUT_SAVE", command.payoutAccountId(), semantics, command.occurredAt());
      return portalPayout(row);
    });
  }

  private static PortalPayoutAccount portalPayout(PayoutAccountRow row) {
    return new PortalPayoutAccount(row.payoutAccountId, row.payoutType, "****",
        mask(row.payoutAccountReference), row.bankName, row.qrCodeReference != null,
        PayoutAccountStatus.valueOf(row.status), row.sourceVersion, instant(row.updatedAt));
  }

  private boolean replayedWithdrawal(RequestWithdrawal request, String semantics) {
    ReceiptRow receipt = receipts.selectById(request.commandId());
    if (receipt != null && receipt.commandType.equals("WITHDRAWAL_REQUEST")
        && receipt.aggregateId.equals(request.withdrawalId())) {
      // Older receipts included the original server timestamp; it was never a caller-controlled field.
      String originalTimestamp = fields(getWithdrawal(request.withdrawalId()).requestedAt());
      if (receipt.semantics.equals(semantics + originalTimestamp)) return true;
    }
    return replayed(request.commandId(), "WITHDRAWAL_REQUEST", request.withdrawalId(), semantics);
  }

  private boolean replayed(String commandId, String type, String aggregateId, String semantics) {
    ReceiptRow receipt = receipts.selectById(commandId);
    if (receipt == null) return false;
    if (!receipt.commandType.equals(type) || !receipt.aggregateId.equals(aggregateId)
        || !receipt.semantics.equals(semantics)) {
      throw new IllegalStateException("idempotency key was replayed with different semantics");
    }
    return true;
  }

  private void record(String commandId, String type, String aggregateId, String semantics, Instant at) {
    sql.recordCommand(commandId, type, aggregateId, semantics, timestamp(at));
  }

  private ApplicationView lockApplication(String id) {
    return sql.lockApplication(id).stream().map(MyBatisPromotionStore::mapApplication).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("application not found"));
  }

  private ApplicantEligibilityView applicantEligibility(String userId, boolean forUpdate) {
    return applicantEligibilityRows(userId, forUpdate).stream().findFirst()
        .orElseThrow(() -> new IllegalStateException("trusted applicant eligibility not found"));
  }

  private List<ApplicantEligibilityView> applicantEligibilityRows(String userId, boolean forUpdate) {
    var rows = forUpdate ? sql.lockEligibility(userId)
        : eligibilities.selectList(new QueryWrapper<ApplicantEligibilityRow>().eq("user_id", userId));
    return rows.stream().map(MyBatisPromotionStore::mapApplicantEligibility).toList();
  }

  private PayoutAccount payoutAccount(String accountId, boolean forUpdate) {
    return payoutAccountRows(accountId, forUpdate).stream().findFirst()
        .orElseThrow(() -> new IllegalStateException("trusted payout account not found"));
  }

  private List<PayoutAccount> payoutAccountRows(String accountId, boolean forUpdate) {
    var rows = forUpdate ? sql.lockPayoutAccount(accountId)
        : payouts.selectList(new QueryWrapper<PayoutAccountRow>().eq("payout_account_id", accountId));
    return rows.stream().map(MyBatisPromotionStore::mapPayoutAccount).toList();
  }

  private PromoterView lockPromoter(String id) {
    return sql.lockPromoter(id).stream().map(this::mapPromoter).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("promoter not found"));
  }

  private WithdrawalView lockWithdrawal(String id) {
    return sql.lockWithdrawal(id).stream().map(MyBatisPromotionStore::mapWithdrawal).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("withdrawal not found"));
  }

  private long currentReferralVersionForUpdate(String userId) {
    var rows = sql.lockCurrentReferral(userId);
    return rows.isEmpty() ? 0 : rows.getFirst().version;
  }

  private List<ReferralView> currentReferral(String userId, boolean forUpdate) {
    var rows = forUpdate ? sql.lockCurrentReferral(userId)
        : referrals.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 1, false),
            new QueryWrapper<ReferralBindingRow>().eq("referred_user_id", userId).orderByDesc("version"))
            .getRecords();
    return rows.stream().map(MyBatisPromotionStore::mapReferral).toList();
  }

  private ReferralRow referralRow(String userId, long version) {
    return referrals.selectList(new QueryWrapper<ReferralBindingRow>()
        .eq("referred_user_id", userId).eq("version", version)).stream()
        .map(row -> new ReferralRow(row.version, row.cashPromoterUserId, row.cashTier)).findFirst()
        .orElseThrow(() -> new IllegalStateException("referral binding snapshot not found"));
  }

  private void rejectCycle(String referredUserId, String inviterUserId) {
    Set<String> visited = new HashSet<>();
    String current = inviterUserId;
    while (current != null) {
      if (current.equals(referredUserId)) throw new IllegalArgumentException("referral cycle is not allowed");
      if (!visited.add(current)) throw new IllegalStateException("stored referral graph contains a cycle");
      List<ReferralView> parent = currentReferral(current, true);
      current = parent.isEmpty() ? null : parent.getFirst().directInviterUserId();
    }
  }

  private boolean enabledPromoter(String userId) {
    return promoters.selectCount(new QueryWrapper<PromoterRow>()
        .eq("promoter_user_id", userId).eq("status", "ENABLED")) > 0;
  }

  private Account lockAccount(String promoterUserId) {
    return sql.lockAccount(promoterUserId).stream().map(MyBatisPromotionStore::mapAccount).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("commission account not found"));
  }

  private Funding lockFunding(String lotId) {
    return sql.lockFunding(lotId).stream().map(MyBatisPromotionStore::mapFunding).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("funding benefit not found"));
  }

  private Funding fundingByEvent(String eventId) {
    return fundingBenefits.selectList(new QueryWrapper<FundingBenefitRow>().eq("source_event_id", eventId))
        .stream().map(MyBatisPromotionStore::mapFunding).findFirst()
        .orElseThrow(() -> new IllegalStateException("recharge ledger has no funding snapshot"));
  }

  private List<CommissionEntryView> ledgerByEvent(String eventId) {
    return ledger.selectList(new QueryWrapper<CommissionLedgerRow>().eq("source_event_id", eventId))
        .stream().map(MyBatisPromotionStore::mapLedger).toList();
  }

  private CommissionView commission(String promoterUserId, BigDecimal changed) {
    Account a = accounts.selectList(new QueryWrapper<CommissionAccountRow>().eq("promoter_user_id", promoterUserId))
        .stream().map(MyBatisPromotionStore::mapAccount).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("commission account not found"));
    return new CommissionView(a.userId, a.frozen, a.available, a.withdrawing, a.withdrawn,
        a.cashDebt, a.version, money(changed));
  }

  private void insertLedger(String promoterId, long sequence, String action, BigDecimal amount,
      String eventId, String orderId, String lotId, String allocationId, String withdrawalId,
      Instant at) {
    sql.insertLedger(promoterId, sequence, action, amount, eventId, orderId, lotId, allocationId,
        withdrawalId, timestamp(at));
  }

  private void insertTransition(String commandId, String withdrawalId, String action,
      String operator, String reason, Instant at, WithdrawalStatus status) {
    sql.insertTransition(commandId, withdrawalId, action, operator, reason, timestamp(at), status.name());
  }

  private <T> T inTransaction(Supplier<T> supplier) {
    return Objects.requireNonNull(transaction.execute(ignored -> supplier.get()));
  }

  private <T> T idempotentTransaction(Supplier<T> supplier) {
    try {
      return inTransaction(supplier);
    } catch (DuplicateKeyException first) {
      try {
        return inTransaction(supplier);
      } catch (DuplicateKeyException repeated) {
        throw new IllegalStateException("unique business key conflict", first);
      }
    }
  }

  private static <R, V> FinancialPage<V> financialPage(BaseMapper<R> mapper, QueryWrapper<R> query,
      String amountColumn, int page, int size, Function<R, V> view, String dateColumn, String keyColumn, boolean keyDescending) {
    var sums = mapper.selectMaps(query.clone().select("COUNT(*) AS records", "COUNT(" + amountColumn + ") AS known",
        "COALESCE(SUM(" + amountColumn + "),0) AS amount")).getFirst();
    long total = ((Number) sums.get("records")).longValue();
    String amount = total == ((Number) sums.get("known")).longValue()
        ? money(new BigDecimal(sums.get("amount").toString())).toPlainString() : null;
    var selected = mapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<R>((long) page + 1, size, false),
        query.orderByDesc(dateColumn).orderBy(true, !keyDescending, keyColumn));
    return new FinancialPage<>(selected.getRecords().stream().map(view).toList(), page, size, total, amount);
  }

  private static <R, V> Page<V> page(BaseMapper<R> mapper, QueryWrapper<R> query,
      int page, int size, Function<R, V> view) {
    var selected = mapper.selectPage(
        new com.baomidou.mybatisplus.extension.plugins.pagination.Page<R>((long) page + 1, size), query);
    return new Page<>(selected.getRecords().stream().map(view).toList(), page, size, selected.getTotal());
  }

  private static ApplicationView mapApplication(ApplicationRow row) {
    return new ApplicationView(row.applicationId, row.userId,
        row.accountType, row.accountStatus, row.reason,
        row.plannedChannel, row.eligibilitySourceVersion,
        Instant.parse(row.eligibilitySnapshotAt), row.status,
        Instant.parse(row.submittedAt), row.decidedBy,
        instant(row.decidedAt), row.rejectionReason);
  }

  private static ApplicantEligibilityView mapApplicantEligibility(ApplicantEligibilityRow row) {
    return new ApplicantEligibilityView(row.userId, row.accountType,
        row.accountStatus, Instant.parse(row.registeredAt),
        row.sourceVersion, Instant.parse(row.updatedAt));
  }

  private static PayoutAccount mapPayoutAccount(PayoutAccountRow row) {
    return new PayoutAccount(row.payoutAccountId, row.ownerUserId,
        row.payoutType, row.payoutAccountName,
        row.payoutAccountReference,
        PayoutAccountStatus.valueOf(row.status), row.sourceVersion,
        Instant.parse(row.updatedAt), row.bankName, row.qrCodeReference);
  }

  private PromoterView mapPromoter(PromoterRow row) {
    return new PromoterView(row.promoterUserId, transaction.execute(s->ensureInvitation(row.promoterUserId)),
        PromoterStatus.valueOf(row.status), Instant.parse(row.createdAt),
        Instant.parse(row.updatedAt));
  }

  private static ReferralView mapReferral(ReferralBindingRow row) {
    return new ReferralView(row.referredUserId, row.directInviterUserId,
        row.level1PromoterUserId, row.level2PromoterUserId,
        row.pointBeneficiaryUserId, row.version,
        Instant.parse(row.effectiveAt));
  }

  private static Account mapAccount(CommissionAccountRow row) {
    return new Account(row.promoterUserId, row.frozen4,
        row.available4, row.withdrawing4,
        row.withdrawn4, row.cashDebt4, row.version);
  }

  private static Funding mapFunding(FundingBenefitRow row) {
    return new Funding(row.sourceEventId, row.orderId,
        row.fundingLotId, row.walletOwnerUserId,
        row.referralBindingVersion, row.benefitPolicyVersion,
        row.promoterUserId,
        row.cashTier, row.cashRate6,
        row.paidAmount4, row.frozenCommission4,
        row.releasedCommission4,
        row.clawedUnreleasedCommission4,
        row.reversedReleasedCommission4,
        row.settledPaidAmount4, row.refundedPaidAmount4,
        Instant.parse(row.occurredAt));
  }

  private static CommissionEntryView mapLedger(CommissionLedgerRow row) {
    return new CommissionEntryView(row.sequenceNo, row.promoterUserId,
        row.actionType, row.amount4, row.sourceEventId,
        row.orderId, row.fundingLotId, row.allocationId,
        row.withdrawalId, Instant.parse(row.createdAt));
  }

  private static WithdrawalView mapWithdrawal(WithdrawalRow row) {
    String reference = row.payoutAccountReference;
    return new WithdrawalView(row.withdrawalId, row.promoterUserId,
        row.amount4, WithdrawalStatus.valueOf(row.status),
        row.payoutAccountId, row.payoutAccountSourceVersion,
        Instant.parse(row.payoutAccountSnapshotAt), row.payoutType,
        mask(row.payoutAccountName), mask(reference), row.rejectionReason,
        Instant.parse(row.requestedAt), Instant.parse(row.updatedAt));
  }

  private static BigDecimal proportional(Funding funding, BigDecimal paid) {
    return funding.frozenCommission.multiply(paid).divide(funding.paidAmount, 4, RoundingMode.DOWN);
  }

  private static BigDecimal money(BigDecimal value) { return value.setScale(4, RoundingMode.DOWN); }
  private static Instant instant(String value) { return value == null ? null : Instant.parse(value); }
  private static String timestamp(Instant value) { return INSTANT_TEXT.format(value); }
  private static String mask(String value) {
    if (value.length() <= 4) return "****";
    return "****" + value.substring(value.length() - 4);
  }
  private static boolean same(ApplicantEligibilityView existing,
      ProjectApplicantEligibility command) {
    return existing.accountType().equals(command.accountType())
        && existing.accountStatus().equals(command.accountStatus())
        && existing.registeredAt().equals(command.registeredAt())
        && existing.updatedAt().equals(command.occurredAt());
  }
  private static boolean same(PayoutAccount existing, ProjectPayoutAccount command) {
    return existing.ownerUserId.equals(command.ownerUserId())
        && existing.payoutType.equals(command.payoutType())
        && existing.accountName.equals(command.payoutAccountName())
        && existing.accountReference.equals(command.payoutAccountReference())
        && existing.status == command.status()
        && existing.updatedAt.equals(command.occurredAt());
  }
  private static String fields(Object... values) {
    StringBuilder result = new StringBuilder();
    for (Object value : values) { String text = String.valueOf(value); result.append(text.length()).append(':').append(text); }
    return result.toString();
  }

  private record PayoutAccount(String id, String ownerUserId, String payoutType,
                               String accountName, String accountReference,
                               PayoutAccountStatus status, long sourceVersion,
                               Instant updatedAt, String bankName, String qrCodeReference) {
    PayoutAccountView view() {
      return new PayoutAccountView(id, ownerUserId, payoutType, accountName,
          mask(accountReference), status, sourceVersion, updatedAt);
    }
  }
  private record ReferralRow(long version, String cashPromoterUserId, String cashTier) {}
  private record Account(String userId, BigDecimal frozen, BigDecimal available,
                         BigDecimal withdrawing, BigDecimal withdrawn, BigDecimal cashDebt,
                         long version) {}
  private record Funding(String sourceEventId, String orderId, String fundingLotId,
                         String walletOwnerUserId, long bindingVersion, long policyVersion,
                         String promoterUserId, String cashTier, BigDecimal cashRate, BigDecimal paidAmount,
                         BigDecimal frozenCommission, BigDecimal releasedCommission,
                         BigDecimal clawedUnreleasedCommission,
                         BigDecimal reversedReleasedCommission,
                         BigDecimal settledPaidAmount, BigDecimal refundedPaidAmount,
                         Instant occurredAt) {}
}
