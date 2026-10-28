package com.xzwl.cloud.promotion.domain.policy;

import com.xzwl.cloud.promotion.domain.identity.PromotionIdentifiers;
import com.xzwl.cloud.promotion.domain.money.Rate6;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One immutable, effective-dated version of the referral benefit policy. */
public final class BenefitPolicyVersion {

  private final String historyId;
  private final long versionNo;
  private final Instant effectiveFrom;
  private final Rate6 globalPointRate6;
  private final Rate6 defaultLevel1CashRate6;
  private final Rate6 defaultLevel2CashRate6;
  private final Map<String, PromoterCashRateOverride> cashRateOverrides;

  public BenefitPolicyVersion(
      String historyId,
      long versionNo,
      Instant effectiveFrom,
      Rate6 globalPointRate6,
      Rate6 defaultLevel1CashRate6,
      Rate6 defaultLevel2CashRate6,
      Map<String, PromoterCashRateOverride> cashRateOverrides) {
    this.historyId = requireNonBlank(historyId, "history id is required");
    if (versionNo < 1) {
      throw new IllegalArgumentException("version number must start at one");
    }
    this.versionNo = versionNo;
    this.effectiveFrom = requireValue(effectiveFrom, "effective time is required");
    this.globalPointRate6 = requireValue(globalPointRate6, "point rate is required");
    this.defaultLevel1CashRate6 =
        requireValue(defaultLevel1CashRate6, "level one cash rate is required");
    this.defaultLevel2CashRate6 =
        requireValue(defaultLevel2CashRate6, "level two cash rate is required");
    this.cashRateOverrides = copyOverrides(cashRateOverrides);
  }

  public String historyId() {
    return historyId;
  }

  public long versionNo() {
    return versionNo;
  }

  public Instant effectiveFrom() {
    return effectiveFrom;
  }

  public Rate6 pointRate() {
    return globalPointRate6;
  }

  public Rate6 globalPointRate6() {
    return globalPointRate6;
  }

  public Rate6 defaultLevel1CashRate6() {
    return defaultLevel1CashRate6;
  }

  public Rate6 defaultLevel2CashRate6() {
    return defaultLevel2CashRate6;
  }

  public Rate6 cashRate(String promoterUserId, CashTier tier) {
    if (tier == null) {
      throw new IllegalArgumentException("cash tier is required");
    }
    if (tier == CashTier.NONE) {
      if (promoterUserId != null) {
        throw new IllegalArgumentException("cash tier NONE cannot have a promoter user id");
      }
      return Rate6.ZERO;
    }
    PromotionIdentifiers.requireId(promoterUserId, "promoterUserId");
    PromoterCashRateOverride override = cashRateOverrides.get(promoterUserId);
    return switch (tier) {
      case NONE -> Rate6.ZERO;
      case LEVEL_1 ->
          override == null ? defaultLevel1CashRate6 : override.level1CashRate6();
      case LEVEL_2 ->
          override == null ? defaultLevel2CashRate6 : override.level2CashRate6();
    };
  }

  public Map<String, PromoterCashRateOverride> cashRateOverrides() {
    return cashRateOverrides;
  }

  private static Map<String, PromoterCashRateOverride> copyOverrides(
      Map<String, PromoterCashRateOverride> source) {
    if (source == null) {
      throw new IllegalArgumentException("cash rate overrides are required");
    }
    Map<String, PromoterCashRateOverride> copy = new LinkedHashMap<>();
    for (Map.Entry<String, PromoterCashRateOverride> entry : source.entrySet()) {
      String key = PromotionIdentifiers.requireId(entry.getKey(), "overrideKey");
      PromoterCashRateOverride override =
          requireValue(entry.getValue(), "cash rate override is required");
      if (!key.equals(override.promoterUserId())) {
        throw new IllegalArgumentException("override key must match its promoter");
      }
      copy.put(key, override);
    }
    return Collections.unmodifiableMap(copy);
  }

  private static String requireNonBlank(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    return value;
  }

  private static <T> T requireValue(T value, String message) {
    if (value == null) {
      throw new IllegalArgumentException(message);
    }
    return value;
  }

  @Override
  public String toString() {
    return "BenefitPolicyVersion{"
        + "historyId='"
        + historyId
        + '\''
        + ", versionNo="
        + versionNo
        + ", effectiveFrom="
        + effectiveFrom
        + ", globalPointRate6="
        + globalPointRate6
        + ", defaultLevel1CashRate6="
        + defaultLevel1CashRate6
        + ", defaultLevel2CashRate6="
        + defaultLevel2CashRate6
        + ", overrideCount="
        + cashRateOverrides.size()
        + '}';
  }
}
