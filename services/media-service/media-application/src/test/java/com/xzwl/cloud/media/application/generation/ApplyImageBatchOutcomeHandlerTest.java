package com.xzwl.cloud.media.application.generation;

import java.math.BigDecimal;
import java.util.List;

public final class ApplyImageBatchOutcomeHandlerTest {

  public static void main(String[] args) {
    allSucceededWithoutRefund();
    mixedOutcomesArePartialWithFailedRefundOnly();
    allFailedRefundsEveryImage();
    freeBatchAllowsZeroRefund();
    rejectsDuplicateImageIndex();
    rejectsEmptyBatch();
    System.out.println("ApplyImageBatchOutcomeHandlerTest passed: 6");
  }

  private static void allSucceededWithoutRefund() {
    ImageBatchOutcome result = handler().handle(new ApplyImageBatchOutcomeCommand(
        List.of(
            ImageItemOutcome.success(0, "tos://image-0"),
            ImageItemOutcome.success(1, "tos://image-1")),
        new BigDecimal("1.2500")));

    assertEquals(ImageBatchStatus.SUCCEEDED, result.status(), "success status");
    assertDecimal("0.0000", result.refundAmount(), "success refund");
    assertEquals(2, result.items().size(), "success item count");
  }

  private static void mixedOutcomesArePartialWithFailedRefundOnly() {
    ImageBatchOutcome result = handler().handle(new ApplyImageBatchOutcomeCommand(
        List.of(
            ImageItemOutcome.success(0, "tos://image-0"),
            ImageItemOutcome.failure(1, "provider rejected"),
            ImageItemOutcome.success(2, "tos://image-2")),
        new BigDecimal("2.34567")));

    assertEquals(ImageBatchStatus.PARTIAL_SUCCESS, result.status(), "partial status");
    assertDecimal("2.3456", result.refundAmount(), "partial refund");
  }

  private static void allFailedRefundsEveryImage() {
    ImageBatchOutcome result = handler().handle(new ApplyImageBatchOutcomeCommand(
        List.of(
            ImageItemOutcome.failure(0, "provider rejected"),
            ImageItemOutcome.failure(1, "provider timeout")),
        new BigDecimal("3.1000")));

    assertEquals(ImageBatchStatus.FAILED, result.status(), "failed status");
    assertDecimal("6.2000", result.refundAmount(), "failed refund");
  }

  private static void freeBatchAllowsZeroRefund() {
    ImageBatchOutcome result = handler().handle(new ApplyImageBatchOutcomeCommand(
        List.of(
            ImageItemOutcome.failure(0, "provider rejected"),
            ImageItemOutcome.success(1, "tos://image-1")),
        BigDecimal.ZERO));

    assertEquals(ImageBatchStatus.PARTIAL_SUCCESS, result.status(), "free batch status");
    assertDecimal("0.0000", result.refundAmount(), "free batch refund");
  }

  private static void rejectsDuplicateImageIndex() {
    try {
      handler().handle(new ApplyImageBatchOutcomeCommand(
          List.of(
              ImageItemOutcome.success(0, "tos://image-0"),
              ImageItemOutcome.failure(0, "duplicate")),
          new BigDecimal("1.0000")));
      throw new AssertionError("duplicate image index should be rejected");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  private static void rejectsEmptyBatch() {
    try {
      handler().handle(new ApplyImageBatchOutcomeCommand(List.of(), new BigDecimal("1.0000")));
      throw new AssertionError("empty image batch should be rejected");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  private static ApplyImageBatchOutcomeHandler handler() {
    return new ApplyImageBatchOutcomeHandler();
  }

  private static void assertEquals(Object expected, Object actual, String label) {
    if (!expected.equals(actual)) {
      throw new AssertionError(label + " expected " + expected + " but was " + actual);
    }
  }

  private static void assertDecimal(String expected, BigDecimal actual, String label) {
    if (new BigDecimal(expected).compareTo(actual) != 0) {
      throw new AssertionError(label + " expected " + expected + " but was " + actual);
    }
  }
}
