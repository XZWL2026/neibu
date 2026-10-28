package com.xzwl.cloud.media.application.generation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class SettleMediaResultHandlerTest {

  public static void main(String[] args) {
    settlesSuccessWithoutRefundAndRefundsFailure();
    refundsOnlyFailedImageUnitsForPartialSuccess();
    System.out.println("SettleMediaResultHandlerTest passed: 2");
  }

  private static void settlesSuccessWithoutRefundAndRefundsFailure() {
    RecordingSettlement settlement = new RecordingSettlement();
    SettleMediaResultHandler handler = new SettleMediaResultHandler(settlement);

    handler.handle(MediaSettlementCommand.success("operation-1", new BigDecimal("6")));
    handler.handle(MediaSettlementCommand.failure("operation-2", new BigDecimal("4")));

    check(settlement.events.equals(List.of("settle:operation-1:6.0000", "refund:operation-2:4.0000")),
        "success settles and failure refunds");
  }

  private static void refundsOnlyFailedImageUnitsForPartialSuccess() {
    RecordingSettlement settlement = new RecordingSettlement();
    SettleMediaResultHandler handler = new SettleMediaResultHandler(settlement);

    handler.handle(MediaSettlementCommand.partial(
        "operation-3", new BigDecimal("9"), new BigDecimal("3")));

    check(settlement.events.equals(List.of("refund:operation-3:3.0000")),
        "partial success refunds only failed images");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }

  private static final class RecordingSettlement implements MediaSettlementPort {
    private final List<String> events = new ArrayList<>();

    @Override
    public void settleSuccess(String operationId, BigDecimal chargedAmount) {
      events.add("settle:" + operationId + ":" + chargedAmount.setScale(4));
    }

    @Override
    public void refund(String operationId, BigDecimal refundAmount) {
      events.add("refund:" + operationId + ":" + refundAmount.setScale(4));
    }
  }
}
