package com.xzwl.cloud.promotion.domain.policy;

import com.xzwl.cloud.promotion.domain.identity.PromotionIdentifiers;
import com.xzwl.cloud.promotion.domain.money.Rate6;
import java.util.Objects;

/** Cash-only rate overrides for one promoter. Point rates are deliberately not configurable here. */
public final class PromoterCashRateOverride {

  private final String promoterUserId;
  private final Rate6 level1CashRate6;
  private final Rate6 level2CashRate6;

  public PromoterCashRateOverride(
      String promoterUserId, Rate6 level1CashRate6, Rate6 level2CashRate6) {
    this.promoterUserId =
        PromotionIdentifiers.requireId(promoterUserId, "promoterUserId");
    this.level1CashRate6 = requireRate(level1CashRate6);
    this.level2CashRate6 = requireRate(level2CashRate6);
  }

  public String promoterUserId() {
    return promoterUserId;
  }

  public Rate6 level1CashRate6() {
    return level1CashRate6;
  }

  public Rate6 level2CashRate6() {
    return level2CashRate6;
  }

  public Rate6 level1CashRate() {
    return level1CashRate6;
  }

  public Rate6 level2CashRate() {
    return level2CashRate6;
  }

  private static Rate6 requireRate(Rate6 value) {
    if (value == null) {
      throw new IllegalArgumentException("cash rate is required");
    }
    return value;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof PromoterCashRateOverride that)) {
      return false;
    }
    return promoterUserId.equals(that.promoterUserId)
        && level1CashRate6.equals(that.level1CashRate6)
        && level2CashRate6.equals(that.level2CashRate6);
  }

  @Override
  public int hashCode() {
    return Objects.hash(promoterUserId, level1CashRate6, level2CashRate6);
  }

  @Override
  public String toString() {
    return "PromoterCashRateOverride{"
        + "level1CashRate6="
        + level1CashRate6
        + ", level2CashRate6="
        + level2CashRate6
        + '}';
  }
}
