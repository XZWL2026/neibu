package com.xzwl.cloud.media.application.generation;

public final class ClassifyMediaProviderPollHandlerTest {

  public static void main(String[] args) {
    classifiesRunningAsPending();
    classifiesSucceededAsCompleted();
    classifiesErrorAsFailed();
    rejectsCompletedWithoutOutput();
    System.out.println("ClassifyMediaProviderPollHandlerTest passed: 4");
  }

  private static void classifiesRunningAsPending() {
    MediaProviderPollResult result = new ClassifyMediaProviderPollHandler()
        .handle(new ClassifyMediaProviderPollCommand("running", "ignored", "ignored"));

    assertEquals(MediaProviderPollResult.Status.PENDING, result.status(), "running status");
    assertEquals(null, result.outputUrl(), "running output");
    assertEquals(null, result.errorMessage(), "running error");
  }

  private static void classifiesSucceededAsCompleted() {
    MediaProviderPollResult result = new ClassifyMediaProviderPollHandler()
        .handle(new ClassifyMediaProviderPollCommand(" SUCCEEDED ", " tos://video ", null));

    assertEquals(MediaProviderPollResult.Status.COMPLETED, result.status(), "success status");
    assertEquals("tos://video", result.outputUrl(), "success output");
  }

  private static void classifiesErrorAsFailed() {
    MediaProviderPollResult result = new ClassifyMediaProviderPollHandler()
        .handle(new ClassifyMediaProviderPollCommand("error", null, " provider rejected "));

    assertEquals(MediaProviderPollResult.Status.FAILED, result.status(), "error status");
    assertEquals("provider rejected", result.errorMessage(), "error message");
  }

  private static void rejectsCompletedWithoutOutput() {
    try {
      new ClassifyMediaProviderPollHandler()
          .handle(new ClassifyMediaProviderPollCommand("completed", " ", null));
      throw new AssertionError("completed result without output should be rejected");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  private static void assertEquals(Object expected, Object actual, String label) {
    if (expected == null ? actual != null : !expected.equals(actual)) {
      throw new AssertionError(label + " expected " + expected + " but was " + actual);
    }
  }
}
