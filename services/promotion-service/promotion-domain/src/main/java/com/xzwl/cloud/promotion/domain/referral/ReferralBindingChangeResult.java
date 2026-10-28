package com.xzwl.cloud.promotion.domain.referral;

import java.util.Objects;

public record ReferralBindingChangeResult(
    boolean stateChanged,
    ReferralBindingVersion version) {

  public ReferralBindingChangeResult {
    version = Objects.requireNonNull(version, "version");
  }
}
