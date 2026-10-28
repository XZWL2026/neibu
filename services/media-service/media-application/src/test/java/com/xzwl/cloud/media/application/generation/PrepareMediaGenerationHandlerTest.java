package com.xzwl.cloud.media.application.generation;

import java.math.BigDecimal;

public final class PrepareMediaGenerationHandlerTest {

  public static void main(String[] args) {
    selectsChannelQuotesCostAndReturnsReady();
    returnsNoChannelWithoutQueryingPricing();
    returnsNoPricingGroupWithoutQueryingPricing();
    returnsNoPricingWhenModelHasNoPrice();
    returnsRequiredAndAvailableBalanceWhenInsufficient();
    rejectsZeroQuantity();
    rejectsNegativeQuantity();
    acceptsZeroUnitPriceAsFree();
    rejectsNegativeUnitPrice();
    System.out.println("PrepareMediaGenerationHandlerTest passed: 9");
  }

  private static void selectsChannelQuotesCostAndReturnsReady() {
    RecordingPort port = new RecordingPort();
    PrepareMediaGenerationHandler handler = new PrepareMediaGenerationHandler(port);

    MediaGenerationPreparationResult result =
        handler.handle(
            new PrepareMediaGenerationCommand(
                new MediaGenerationIdentity(9L, new BigDecimal("20")),
                "image-model",
                "1K",
                new BigDecimal("3"),
                null));

    assertEquals(MediaGenerationPreparationResult.Status.READY, result.status(), "status");
    assertSame(port.channel, result.channel(), "channel");
    assertDecimal("6.0000", result.cost(), "cost");
    assertEquals("image-model", port.selectedModel, "selected model");
    assertEquals("image-model", port.pricedModel, "priced model");
    assertEquals("1K", port.pricedResolution, "priced resolution");
  }

  private static void returnsNoChannelWithoutQueryingPricing() {
    RecordingPort port = new RecordingPort();
    port.channel = null;

    MediaGenerationPreparationResult result =
        new PrepareMediaGenerationHandler(port)
            .handle(command(new MediaGenerationIdentity(9L, new BigDecimal("20"))));

    assertEquals(MediaGenerationPreparationResult.Status.NO_CHANNEL, result.status(), "status");
    assertEquals(0, port.pricingCalls, "pricing calls");
  }

  private static void returnsNoPricingGroupWithoutQueryingPricing() {
    RecordingPort port = new RecordingPort();

    MediaGenerationPreparationResult result =
        new PrepareMediaGenerationHandler(port)
            .handle(command(new MediaGenerationIdentity(null, new BigDecimal("20"))));

    assertEquals(
        MediaGenerationPreparationResult.Status.NO_PRICING_GROUP, result.status(), "status");
    assertEquals(0, port.pricingCalls, "pricing calls");
  }

  private static void returnsNoPricingWhenModelHasNoPrice() {
    RecordingPort port = new RecordingPort();
    port.pricing = null;

    MediaGenerationPreparationResult result =
        new PrepareMediaGenerationHandler(port)
            .handle(command(new MediaGenerationIdentity(9L, new BigDecimal("20"))));

    assertEquals(MediaGenerationPreparationResult.Status.NO_PRICING, result.status(), "status");
  }

  private static void returnsRequiredAndAvailableBalanceWhenInsufficient() {
    RecordingPort port = new RecordingPort();

    MediaGenerationPreparationResult result =
        new PrepareMediaGenerationHandler(port)
            .handle(command(new MediaGenerationIdentity(9L, new BigDecimal("5"))));

    assertEquals(
        MediaGenerationPreparationResult.Status.INSUFFICIENT_BALANCE, result.status(), "status");
    assertDecimal("6.0000", result.required(), "required");
    assertDecimal("5", result.balance(), "balance");
  }

  private static void rejectsZeroQuantity() {
    RecordingPort port = new RecordingPort();
    try {
      new PrepareMediaGenerationHandler(port)
          .handle(command(new MediaGenerationIdentity(9L, new BigDecimal("20")), BigDecimal.ZERO));
      throw new AssertionError("zero quantity should be rejected");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  private static void rejectsNegativeQuantity() {
    RecordingPort port = new RecordingPort();
    try {
      new PrepareMediaGenerationHandler(port)
          .handle(
              command(
                  new MediaGenerationIdentity(9L, new BigDecimal("20")),
                  new BigDecimal("-1")));
      throw new AssertionError("negative quantity should be rejected");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  private static void acceptsZeroUnitPriceAsFree() {
    MediaPricing pricing = new MediaPricing(BigDecimal.ZERO);
    assertDecimal("0", pricing.pricePerUnit(), "free unit price");
  }

  private static void rejectsNegativeUnitPrice() {
    try {
      new MediaPricing(new BigDecimal("-0.01"));
      throw new AssertionError("negative unit price should be rejected");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  private static PrepareMediaGenerationCommand command(MediaGenerationIdentity identity) {
    return command(identity, new BigDecimal("3"));
  }

  private static PrepareMediaGenerationCommand command(
      MediaGenerationIdentity identity, BigDecimal quantity) {
    return new PrepareMediaGenerationCommand(identity, "image-model", null, quantity, null);
  }

  private static void assertEquals(Object expected, Object actual, String label) {
    if (!expected.equals(actual)) {
      throw new AssertionError(label + " expected " + expected + " but was " + actual);
    }
  }

  private static void assertDecimal(String expected, BigDecimal actual, String label) {
    if (actual == null || new BigDecimal(expected).compareTo(actual) != 0) {
      throw new AssertionError(label + " expected " + expected + " but was " + actual);
    }
  }

  private static void assertSame(Object expected, Object actual, String label) {
    if (expected != actual) {
      throw new AssertionError(label + " expected same instance");
    }
  }

  private static final class RecordingPort implements MediaGenerationPreparationPort {

    private MediaChannelSelection channel =
        new MediaChannelSelection(1L, 2L, "image-model", "upstream-image-model");
    private MediaPricing pricing = new MediaPricing(new BigDecimal("2"));
    private String selectedModel;
    private String pricedModel;
    private String pricedResolution;
    private int pricingCalls;

    @Override
    public MediaChannelSelection selectChannel(String requestedModel) {
      selectedModel = requestedModel;
      return channel;
    }

    @Override
    public MediaPricing findPricing(Long pricingGroupId, String requestedModel, String resolution) {
      pricingCalls++;
      pricedModel = requestedModel;
      pricedResolution = resolution;
      return pricing;
    }
  }
}
