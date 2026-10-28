package com.xzwl.cloud.promotion.domain.reward;

import static org.assertj.core.api.Assertions.assertThat;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PointRewardConcurrencyTest {

  private static final Instant NOW = Instant.parse("2026-08-30T14:00:00Z");

  @Test
  void thirtyTwoConcurrentCopiesOfSameGrantProduceOneFact() throws Exception {
    PointRewardAccount account = PointRewardAccount.create("beneficiary");
    List<PointRewardWriteResult> results =
        race(
            32,
            index ->
                () ->
                    account.grant(
                        "same-event",
                        "same-entitlement",
                        "settlement",
                        "allocation",
                        "funding-lot",
                        "consumer",
                        1,
                        CashTier.NONE,
                        amount("1"),
                        NOW));

    assertThat(results).hasSize(32);
    assertThat(results).allSatisfy(result -> assertThat(result.entries()).hasSize(1));
    assertThat(account.entries()).hasSize(1);
    assertThat(account.currentSnapshot().cumulativeGranted()).isEqualTo(amount("1"));
  }

  @Test
  void thirtyTwoDifferentGrantsAreLinearizedWithoutLosingFacts() throws Exception {
    PointRewardAccount account = PointRewardAccount.create("beneficiary");
    List<PointRewardWriteResult> results =
        race(
            32,
            index ->
                () ->
                    account.grant(
                        "event-" + index,
                        "entitlement-" + index,
                        "settlement-" + index,
                        "allocation-" + index,
                        "funding-lot-" + index,
                        "consumer-" + index,
                        index + 1L,
                        CashTier.values()[index % CashTier.values().length],
                        amount("1"),
                        NOW));

    assertThat(results).hasSize(32);
    assertThat(account.entries()).hasSize(32);
    assertThat(account.entries()).extracting(ReferralPointEntitlement::sequenceNo)
        .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 32).boxed().toList());
    assertThat(account.currentSnapshot().cumulativeGranted()).isEqualTo(amount("32"));
  }

  @Test
  void concurrentReversalsCannotOverReverseOneEntitlement() throws Exception {
    PointRewardAccount account = PointRewardAccount.create("beneficiary");
    account.grant(
        "grant",
        "entitlement",
        "settlement",
        "allocation",
        "funding-lot",
        "consumer",
        1,
        CashTier.LEVEL_1,
        amount("10"),
        NOW);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Boolean>> outcomes = new ArrayList<>();
      for (int index = 0; index < 2; index++) {
        int attempt = index;
        outcomes.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  try {
                    account.reverse(
                        "reverse-" + attempt,
                        "entitlement",
                        "settlement",
                        "allocation",
                        "funding-lot",
                        "consumer",
                        1,
                        CashTier.LEVEL_1,
                        amount("7"),
                        amount("7"),
                        NOW);
                    return true;
                  } catch (IllegalStateException expected) {
                    return false;
                  }
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(outcomes).extracting(future -> future.get(10, TimeUnit.SECONDS))
          .containsExactlyInAnyOrder(true, false);
    } finally {
      pool.shutdownNow();
    }

    assertThat(account.currentSnapshot().cumulativeReversed()).isEqualTo(amount("7"));
    assertThat(account.entries()).hasSize(2);
  }

  private static List<PointRewardWriteResult> race(
      int count, java.util.function.IntFunction<Callable<PointRewardWriteResult>> operation)
      throws Exception {
    CountDownLatch ready = new CountDownLatch(count);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(count);
    try {
      List<Future<PointRewardWriteResult>> futures = new ArrayList<>();
      for (int index = 0; index < count; index++) {
        Callable<PointRewardWriteResult> command = operation.apply(index);
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return command.call();
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<PointRewardWriteResult> results = new ArrayList<>();
      for (Future<PointRewardWriteResult> future : futures) {
        results.add(future.get(10, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  private static Decimal4 amount(String value) {
    return Decimal4.parse(value);
  }
}
