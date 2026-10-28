package com.xzwl.cloud.promotion.domain.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WithdrawalConstructionBoundaryTest {

  @Test
  void balanceActionsAndTransitionResultsCannotBeForgedOutsideTheDomainPackage() {
    assertThat(WithdrawalBalanceAction.class.getConstructors()).isEmpty();
    assertThat(WithdrawalTransitionResult.class.getConstructors()).isEmpty();
  }
}
