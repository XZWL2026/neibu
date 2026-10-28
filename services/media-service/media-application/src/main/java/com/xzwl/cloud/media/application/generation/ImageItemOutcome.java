package com.xzwl.cloud.media.application.generation;

/** Terminal result for one image in a multi-image generation request. */
public record ImageItemOutcome(int index, String outputUrl, String failureMessage) {

  public ImageItemOutcome {
    if (index < 0) {
      throw new IllegalArgumentException("index must not be negative");
    }
    boolean hasOutput = outputUrl != null && !outputUrl.isBlank();
    boolean hasFailure = failureMessage != null && !failureMessage.isBlank();
    if (hasOutput == hasFailure) {
      throw new IllegalArgumentException("an image item must have exactly one terminal outcome");
    }
    outputUrl = hasOutput ? outputUrl : null;
    failureMessage = hasFailure ? failureMessage : null;
  }

  public static ImageItemOutcome success(int index, String outputUrl) {
    return new ImageItemOutcome(index, outputUrl, null);
  }

  public static ImageItemOutcome failure(int index, String failureMessage) {
    return new ImageItemOutcome(index, null, failureMessage);
  }

  public boolean succeeded() {
    return outputUrl != null;
  }
}
