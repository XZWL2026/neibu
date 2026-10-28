package com.xzwl.cloud.media.application.worker;

import java.time.Instant;

public final class PlanVideoProviderPollHandlerTest {

  private static final Instant STARTED_AT = Instant.parse("2026-09-04T07:00:00Z");
  private static final Instant DEADLINE_AT = Instant.parse("2026-09-04T13:00:00Z");

  public static void main(String[] args) {
    pollsImmediatelyWithoutPreviousPoll();
    waitsUntilFiveSecondsElapsed();
    pollsWhenFiveSecondsElapsed();
    timesOutAtSixHourDeadline();
    rejectsInvalidPollTimeline();
    System.out.println("PlanVideoProviderPollHandlerTest passed: 5");
  }

  private static void pollsImmediatelyWithoutPreviousPoll() {
    VideoProviderPollPlan plan = handler().handle(
        new PlanVideoProviderPollCommand(STARTED_AT, null, STARTED_AT));

    assertEquals(VideoProviderPollPlan.Status.POLL_NOW, plan.status(), "first poll status");
    assertEquals(null, plan.nextPollAt(), "first poll next time");
    assertEquals(DEADLINE_AT, plan.deadlineAt(), "first poll deadline");
  }

  private static void waitsUntilFiveSecondsElapsed() {
    Instant lastPolledAt = STARTED_AT.plusSeconds(10);
    VideoProviderPollPlan plan = handler().handle(
        new PlanVideoProviderPollCommand(STARTED_AT, lastPolledAt, lastPolledAt.plusSeconds(4)));

    assertEquals(VideoProviderPollPlan.Status.WAIT, plan.status(), "wait status");
    assertEquals(lastPolledAt.plusSeconds(5), plan.nextPollAt(), "next poll time");
  }

  private static void pollsWhenFiveSecondsElapsed() {
    Instant lastPolledAt = STARTED_AT.plusSeconds(10);
    VideoProviderPollPlan plan = handler().handle(
        new PlanVideoProviderPollCommand(STARTED_AT, lastPolledAt, lastPolledAt.plusSeconds(5)));

    assertEquals(VideoProviderPollPlan.Status.POLL_NOW, plan.status(), "due poll status");
    assertEquals(null, plan.nextPollAt(), "due poll next time");
  }

  private static void timesOutAtSixHourDeadline() {
    VideoProviderPollPlan plan = handler().handle(
        new PlanVideoProviderPollCommand(STARTED_AT, DEADLINE_AT.minusSeconds(1), DEADLINE_AT));

    assertEquals(VideoProviderPollPlan.Status.TIMED_OUT, plan.status(), "timeout status");
    assertEquals(null, plan.nextPollAt(), "timeout next time");
    assertEquals(DEADLINE_AT, plan.deadlineAt(), "timeout deadline");
  }

  private static void rejectsInvalidPollTimeline() {
    try {
      handler().handle(new PlanVideoProviderPollCommand(
          STARTED_AT, STARTED_AT.minusSeconds(1), STARTED_AT));
      throw new AssertionError("poll before start should be rejected");
    } catch (IllegalArgumentException expected) {
      // expected
    }
    try {
      handler().handle(new PlanVideoProviderPollCommand(
          STARTED_AT, STARTED_AT.plusSeconds(1), STARTED_AT));
      throw new AssertionError("poll after current time should be rejected");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  private static PlanVideoProviderPollHandler handler() {
    return new PlanVideoProviderPollHandler(new MediaExecutionDeadlinePolicy());
  }

  private static void assertEquals(Object expected, Object actual, String label) {
    if (expected == null ? actual != null : !expected.equals(actual)) {
      throw new AssertionError(label + " expected " + expected + " but was " + actual);
    }
  }
}
