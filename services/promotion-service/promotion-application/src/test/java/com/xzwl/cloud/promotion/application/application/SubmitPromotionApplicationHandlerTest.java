package com.xzwl.cloud.promotion.application.application;

import com.xzwl.cloud.promotion.domain.application.AccountStatus;
import com.xzwl.cloud.promotion.domain.application.AccountType;
import com.xzwl.cloud.promotion.domain.application.ApplicantSnapshot;
import com.xzwl.cloud.promotion.domain.application.ApplicationStatus;
import com.xzwl.cloud.promotion.domain.application.PromotionApplication;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SubmitPromotionApplicationHandlerTest {

  public static void main(String[] args) {
    submitsEligibleApplicationAndSavesIt();
    System.out.println("SubmitPromotionApplicationHandlerTest passed: 1");
  }

  private static void submitsEligibleApplicationAndSavesIt() {
    Instant registeredAt = Instant.parse("2026-09-01T08:00:00Z");
    Instant submittedAt = Instant.parse("2026-09-04T08:00:00Z");
    ApplicantSnapshot applicant =
        new ApplicantSnapshot(
            "user-1",
            Optional.empty(),
            Optional.of("推广人"),
            Optional.empty(),
            Optional.empty(),
            registeredAt,
            AccountType.PERSONAL,
            AccountStatus.ENABLED);
    RecordingStore store = new RecordingStore();

    PromotionApplication application =
        new SubmitPromotionApplicationHandler(store)
            .handle(
                new SubmitPromotionApplicationCommand(
                    "application-1",
                    applicant,
                    "分享工具",
                    "社群",
                    true,
                    submittedAt));

    assertEquals(ApplicationStatus.PENDING, application.status(), "status");
    assertEquals("application-1", application.id(), "id");
    assertEquals("user-1", application.applicantUserId(), "applicant");
    assertEquals("分享工具", application.reason(), "reason");
    assertEquals("社群", application.plannedChannel(), "planned channel");
    assertTrue(application.agreementsAccepted(), "agreements accepted");
    assertEquals(submittedAt, application.submittedAt(), "submitted at");
    assertSame(application, store.saved, "saved aggregate");
    assertEquals(List.of("save"), store.calls, "store calls");
  }

  private static void assertTrue(boolean condition, String label) {
    if (!condition) {
      throw new AssertionError(label + " expected true");
    }
  }

  private static void assertEquals(Object expected, Object actual, String label) {
    if (!expected.equals(actual)) {
      throw new AssertionError(label + " expected " + expected + " but was " + actual);
    }
  }

  private static void assertSame(Object expected, Object actual, String label) {
    if (expected != actual) {
      throw new AssertionError(label + " expected same instance");
    }
  }

  private static final class RecordingStore implements PromotionApplicationSubmissionStore {

    private final List<String> calls = new ArrayList<>();
    private PromotionApplication saved;

    @Override
    public void save(PromotionApplication application) {
      calls.add("save");
      saved = application;
    }
  }
}
