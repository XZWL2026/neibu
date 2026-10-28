package com.xzwl.cloud.billing.infrastructure;

import com.xzwl.cloud.billing.application.funding.ConsumeFundingHandler;
import com.xzwl.cloud.billing.application.funding.ConsumeFundingCommand;
import com.xzwl.cloud.billing.application.funding.RefundFundingHandler;
import com.xzwl.cloud.billing.application.funding.RefundFundingCommand;
import com.xzwl.cloud.billing.domain.funding.FundingLot;
import com.xzwl.cloud.billing.domain.funding.FundingLotType;
import com.xzwl.cloud.billing.domain.funding.FundingRefundRequest;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.time.Instant;

public final class InMemoryFundingPersistenceTest {

  public static void main(String[] args) {
    consumeAndRefundRoundTripPersistsTheFundingState();
    System.out.println("InMemoryFundingPersistenceTest passed: 1");
  }

  private static void consumeAndRefundRoundTripPersistsTheFundingState() {
    InMemoryFundingPersistence persistence = new InMemoryFundingPersistence();
    persistence.creditLot(
        new FundingLot(
            "lot-1",
            "wallet-1",
            FundingLotType.REAL_RECHARGE,
            Decimal4.of("10.0000"),
            Decimal4.of("10.0000"),
            Instant.parse("2026-09-07T00:00:00Z"),
            true));

    ConsumeFundingHandler consumer = new ConsumeFundingHandler(persistence);
    var consumed =
        consumer.handle(
            new ConsumeFundingCommand("wallet-1", "operation-1", Decimal4.of("4.0000")));
    check(
        persistence.loadLots("wallet-1").getFirst().remainingPoints4().equals(Decimal4.of("6.0000")),
        "consumption replaces remaining lot balance");

    RefundFundingHandler refunder = new RefundFundingHandler(persistence);
    var request = consumed.allocations().getFirst();
    refunder.handle(
        new RefundFundingCommand(
            "wallet-1",
            "refund-1",
            java.util.List.of(
                new FundingRefundRequest(
                    "refund-allocation-1",
                    request.operationId(),
                    request.lotId(),
                    request.allocatedPoints4()))));
    var replay =
        refunder.handle(
            new RefundFundingCommand(
                "wallet-1",
                "refund-1",
                java.util.List.of(
                    new FundingRefundRequest(
                        "refund-allocation-1",
                        request.operationId(),
                        request.lotId(),
                        request.allocatedPoints4()))));

    check(
        persistence.loadLots("wallet-1").getFirst().remainingPoints4().equals(Decimal4.of("10.0000")),
        "refund restores the consumed balance");
    check(replay == persistence.findRefund("wallet-1", "refund-1").orElseThrow(),
        "refund replay returns the persisted plan");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }
}
