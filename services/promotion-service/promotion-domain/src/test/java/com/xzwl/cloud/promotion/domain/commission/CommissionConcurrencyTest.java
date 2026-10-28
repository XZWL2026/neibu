package com.xzwl.cloud.promotion.domain.commission;

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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CommissionConcurrencyTest {

  private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");

  @Test
  void serialAndConcurrentExactReplayAppendOnlyOnce() throws Exception {
    CommissionAccount account = CommissionAccount.create("promoter");
    CommissionEntry first =
        account.freezeCommission("event", "order", "lot", amount("10"), 1, CashTier.LEVEL_1, NOW);
    for (int index = 0; index < 100; index++) {
      assertThat(
              account.freezeCommission(
                  "event", "order", "lot", amount("10"), 1, CashTier.LEVEL_1, NOW))
          .isSameAs(first);
    }

    List<CommissionEntry> concurrent =
        invokeTogether(
            32,
            () ->
                account.freezeCommission(
                    "event", "order", "lot", amount("10"), 1, CashTier.LEVEL_1, NOW));

    assertThat(concurrent).allMatch(entry -> entry == first);
    assertThat(account.entries()).containsExactly(first);
  }

  @Test
  void concurrentDifferentFreezesAndReleasesLoseNoSequence() throws Exception {
    CommissionAccount account = CommissionAccount.create("promoter");
    AtomicInteger next = new AtomicInteger();
    invokeTogether(
        32,
        () -> {
          int index = next.getAndIncrement();
          return account.freezeCommission(
              "freeze-" + index,
              "order-" + index,
              "lot-" + index,
              amount("1"),
              index + 1L,
              CashTier.LEVEL_1,
              NOW);
        });
    assertThat(account.currentSnapshot().frozen()).isEqualTo(amount("32"));

    AtomicInteger releaseNext = new AtomicInteger();
    invokeTogether(
        32,
        () -> {
          int index = releaseNext.getAndIncrement();
          return account.releaseCommission(
              "release-" + index,
              "order-" + index,
              "lot-" + index,
              "allocation-" + index,
              amount("1"),
              NOW);
        });

    assertThat(account.entries()).hasSize(64);
    assertThat(account.entries())
        .extracting(CommissionEntry::sequenceNo)
        .containsExactlyElementsOf(longsFromOneTo(64));
    assertThat(account.currentSnapshot().frozen()).isEqualTo(Decimal4.ZERO);
    assertThat(account.currentSnapshot().available()).isEqualTo(amount("32"));
  }

  @Test
  void concurrentCompoundReleaseReplayAppendsReleaseAndOffsetOnlyOnce() throws Exception {
    CommissionAccount account = fundedAvailable("10");
    account.requestWithdrawal("withdraw", "request", amount("10"), NOW);
    account.reverseRelease("reverse", "allocation", amount("10"), NOW);
    account.freezeCommission(
        "freeze-next", "order-next", "lot-next", amount("6"), 2, CashTier.LEVEL_1, NOW);

    List<CommissionWriteResult> results =
        invokeTogether(
            32,
            () ->
                account.releaseCommission(
                    "release-next",
                    "order-next",
                    "lot-next",
                    "allocation-next",
                    amount("6"),
                    NOW));

    assertThat(results).allMatch(result -> result.entries().size() == 2);
    CommissionEntry released = results.getFirst().entries().getFirst();
    CommissionEntry offset = results.getFirst().entries().getLast();
    assertThat(results)
        .allMatch(
            result ->
                result.entries().getFirst() == released && result.entries().getLast() == offset);
    assertThat(account.entries().stream().filter(entry -> entry.sourceEventId().equals("release-next")))
        .extracting(CommissionEntry::actionType)
        .containsExactly(
            CommissionActionType.COMMISSION_RELEASED, CommissionActionType.CASH_DEBT_OFFSET);
  }

  @Test
  void concurrentReversalsCannotExceedOneAllocation() throws Exception {
    CommissionAccount account = fundedAvailable("10");
    AtomicInteger next = new AtomicInteger();
    List<Attempt> attempts =
        invokeTogether(
            32,
            () -> {
              int index = next.getAndIncrement();
              try {
                account.reverseRelease("reverse-" + index, "allocation", amount("1"), NOW);
                return Attempt.SUCCESS;
              } catch (IllegalStateException expected) {
                return Attempt.REJECTED;
              }
            });

    assertThat(attempts).filteredOn(Attempt.SUCCESS::equals).hasSize(10);
    assertThat(attempts).filteredOn(Attempt.REJECTED::equals).hasSize(22);
    assertThat(account.currentSnapshot().available()).isEqualTo(Decimal4.ZERO);
    assertThat(account.currentSnapshot().cashDebt()).isEqualTo(Decimal4.ZERO);
  }

  @Test
  void concurrentWithdrawalRequestsCannotOverdrawAvailable() throws Exception {
    CommissionAccount account = fundedAvailable("10");
    AtomicInteger next = new AtomicInteger();
    List<Attempt> attempts =
        invokeTogether(
            2,
            () -> {
              int index = next.getAndIncrement();
              try {
                account.requestWithdrawal(
                    "withdraw-" + index, "request-" + index, amount("7"), NOW);
                return Attempt.SUCCESS;
              } catch (IllegalStateException expected) {
                return Attempt.REJECTED;
              }
            });

    assertThat(attempts).filteredOn(Attempt.SUCCESS::equals).hasSize(1);
    assertThat(attempts).filteredOn(Attempt.REJECTED::equals).hasSize(1);
    assertThat(account.currentSnapshot().available()).isEqualTo(amount("3"));
    assertThat(account.currentSnapshot().withdrawing()).isEqualTo(amount("7"));
  }

  private static CommissionAccount fundedAvailable(String amount) {
    CommissionAccount account = CommissionAccount.create("promoter");
    account.freezeCommission("freeze", "order", "lot", amount(amount), 1, CashTier.LEVEL_1, NOW);
    account.releaseCommission("release", "order", "lot", "allocation", amount(amount), NOW);
    return account;
  }

  private static <T> List<T> invokeTogether(int count, Callable<T> operation) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(count);
    CountDownLatch ready = new CountDownLatch(count);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<T>> futures = new ArrayList<>();
      for (int index = 0; index < count; index++) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("workers did not start together");
                  }
                  return operation.call();
                }));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get(10, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static List<Long> longsFromOneTo(int endInclusive) {
    List<Long> values = new ArrayList<>();
    for (long value = 1; value <= endInclusive; value++) {
      values.add(value);
    }
    return values;
  }

  private static Decimal4 amount(String value) {
    return Decimal4.parse(value);
  }

  private enum Attempt {
    SUCCESS,
    REJECTED
  }
}
