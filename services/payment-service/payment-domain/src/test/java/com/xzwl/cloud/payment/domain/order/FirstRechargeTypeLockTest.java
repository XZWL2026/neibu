package com.xzwl.cloud.payment.domain.order;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class FirstRechargeTypeLockTest {

  @org.junit.jupiter.api.Test
  void runsExistingBehaviorChecks() throws Exception { main(new String[0]); }

  public static void main(String[] args) throws Exception {
    FirstRechargeTypeLock lock = FirstRechargeTypeLock.create("account-1");
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger personalWins = new AtomicInteger();
    AtomicInteger enterpriseConflicts = new AtomicInteger();
    AtomicInteger personalConflicts = new AtomicInteger();
    Thread personal = new Thread(() -> {
      await(start);
      try {
        if (lock.lock(RechargeType.PERSONAL)) personalWins.incrementAndGet();
      } catch (IllegalStateException expected) {
        personalConflicts.incrementAndGet();
      }
    });
    Thread enterprise = new Thread(() -> {
      await(start);
      try {
        lock.lock(RechargeType.ENTERPRISE);
      } catch (IllegalStateException expected) {
        enterpriseConflicts.incrementAndGet();
      }
    });
    personal.start();
    enterprise.start();
    start.countDown();
    personal.join();
    enterprise.join();

    check(personalWins.get() + (enterpriseConflicts.get() == 0 ? 1 : 0) == 1,
        "one concurrent type wins");
    check(personalConflicts.get() + enterpriseConflicts.get() == 1, "one concurrent type conflicts");
    check(lock.lockedType() != null, "lock has one type");
    check(lock.lock(lock.lockedType()) == false, "same type is an idempotent no-op");
    System.out.println("FirstRechargeTypeLockTest passed: 1");
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }
}
