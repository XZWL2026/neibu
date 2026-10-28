package com.xzwl.cloud.promotion;

import com.xzwl.cloud.promotion.application.production.PromotionStore.*;
import java.util.function.Function;
import org.springframework.web.client.RestClient;

/** Joins only service APIs after the promotion store selects a current owner-scoped relation. */
final class PromotionPortalDirectory implements Function<String, PortalDownlineFacts> {
  private final RestClient users;
  private final RestClient billing;
  private RestClient payment;
  void setPayment(RestClient payment) { this.payment=payment; }
  LiveApplicant applicant(String owner) {
    var profile=users.get().uri("/internal/v1/users/{id}/promotion-profile",owner).retrieve().body(Profile.class);
    if(profile==null||profile.user()==null||!owner.equals(profile.user().id())||profile.registeredAt()==null)throw new IllegalStateException("invalid applicant response");
    var identity=payment.get().uri("/internal/v1/payments/accounts/{id}/identity",owner).retrieve().body(Identity.class);
    if(identity==null||!owner.equals(identity.ownerId()))throw new IllegalStateException("invalid account identity");
    var user=profile.user(); String phone=user.phone()==null?null:user.phone().replaceFirst("^\\+86", "");
    String masked=phone==null?null:phone.length()<7?"****":phone.substring(0,3)+"****"+phone.substring(phone.length()-4);
    return new LiveApplicant(owner,identity.accountType()==null?"UNSET":identity.accountType(),user.enabled(),profile.registeredAt(),user.authVersion()+1,user.nickname(),masked);
  }
  private record Identity(String ownerId,String accountType) {}
  private record Profile(User user,java.time.Instant registeredAt) {}
  PromotionPortalDirectory(RestClient users, RestClient billing) { this.users = users; this.billing = billing; }
  boolean enabledUser(String owner) {
    User user;
    try {
      user = users.get().uri("/internal/v1/users/{id}", owner).retrieve().body(User.class);
    } catch (org.springframework.web.client.HttpClientErrorException.NotFound missing) {
      throw new IllegalArgumentException("user not found");
    }
    if (user == null || !owner.equals(user.id())) throw new IllegalStateException("user identity mismatch");
    return user.enabled();
  }
  @Override public PortalDownlineFacts apply(String owner) {
    Profile profile = users.get().uri("/internal/v1/users/{id}/promotion-profile", owner).retrieve().body(Profile.class);
    User user = profile == null ? null : profile.user();
    if (user == null || !owner.equals(user.id())) throw new IllegalStateException("downline identity mismatch");
    Summary billingSummary = billing.get().uri("/internal/v1/billing/owners/{id}/portal-summary", owner).retrieve().body(Summary.class);
    if (billingSummary == null || !owner.equals(billingSummary.ownerId()) || billingSummary.summary() == null) {
      throw new IllegalStateException("downline billing identity mismatch");
    }
    String phone = user.phone();
    String masked = phone == null ? null : phone.length() <= 7 ? "****" : phone.substring(0,3) + "****" + phone.substring(phone.length()-4);
    return new PortalDownlineFacts(user.nickname(), masked, user.enabled(), billingSummary.summary().totalRechargeAmount4(), billingSummary.summary().netPoints4(), profile.registeredAt());
  }
  PortalAllocationFacts allocation(PortalAllocationLookup lookup) {
    Allocation fact;
    try {
      fact=billing.get().uri(builder -> builder.path("/internal/v1/billing/owners/{owner}/allocations/{allocation}")
          .queryParam("fundingLotId",lookup.fundingLotId()).build(lookup.ownerId(),lookup.allocationId()))
          .retrieve().body(Allocation.class);
    } catch(org.springframework.web.client.HttpClientErrorException.NotFound unmatched) { return null; }
    if(fact==null || !lookup.ownerId().equals(fact.ownerId()) || !lookup.allocationId().equals(fact.allocationId())
        || !lookup.fundingLotId().equals(fact.fundingLotId())) throw new IllegalStateException("allocation identity mismatch");
    return new PortalAllocationFacts(fact.type(),fact.points4(),fact.balanceBeforePoints4(),fact.balanceAfterPoints4(),fact.balanceBeforeAmount4(),fact.balanceAfterAmount4());
  }
  private record Allocation(String ownerId, String allocationId, String fundingLotId, String type,
      String points4, String balanceBeforePoints4, String balanceAfterPoints4, String balanceBeforeAmount4, String balanceAfterAmount4) {}
  private record User(String id, String nickname, String phone, boolean enabled, long authVersion) {}
  private record Summary(String ownerId, Amounts summary) {}
  private record Amounts(String totalRechargeAmount4, String netPoints4) {}
}
