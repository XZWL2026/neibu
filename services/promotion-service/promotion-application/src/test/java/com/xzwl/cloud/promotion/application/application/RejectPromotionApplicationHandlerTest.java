package com.xzwl.cloud.promotion.application.application;

import com.xzwl.cloud.promotion.domain.application.AccountStatus;
import com.xzwl.cloud.promotion.domain.application.AccountType;
import com.xzwl.cloud.promotion.domain.application.ApplicantSnapshot;
import com.xzwl.cloud.promotion.domain.application.ApplicationStatus;
import com.xzwl.cloud.promotion.domain.application.DecisionResult;
import com.xzwl.cloud.promotion.domain.application.PromotionApplication;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class RejectPromotionApplicationHandlerTest {

  private static final Instant REGISTERED_AT = Instant.parse("2026-08-01T01:00:00Z");
  private static final Instant SUBMITTED_AT = Instant.parse("2026-08-30T01:00:00Z");
  private static final Instant DECIDED_AT = Instant.parse("2026-08-30T02:00:00Z");

  public static void main(String[] args) {
    rejectsLoadedApplicationAndSavesIt();
    System.out.println("RejectPromotionApplicationHandlerTest passed: 1");
  }

  private static void rejectsLoadedApplicationAndSavesIt() {
    RecordingStore store = new RecordingStore(pendingApplication());

    DecisionResult result =
        new RejectPromotionApplicationHandler(store)
            .handle(
                new RejectPromotionApplicationCommand(
                    "application-1", "admin-1", "资料不完整", DECIDED_AT));

    check(result.stateChanged(), "rejection changes state");
    check(result.status() == ApplicationStatus.REJECTED, "rejection result status");
    check(
        store.application.rejectionReason().orElseThrow().equals("资料不完整"),
        "rejection reason is preserved");
    check(store.saved == store.application, "rejected application is saved");
    check(store.calls.equals(List.of("load", "save")), "load and save order");
  }

  private static PromotionApplication pendingApplication() {
    return PromotionApplication.submit(
        "application-1",
        new ApplicantSnapshot(
            "user-1",
            Optional.empty(),
            Optional.of("张三"),
            Optional.empty(),
            Optional.empty(),
            REGISTERED_AT,
            AccountType.PERSONAL,
            AccountStatus.ENABLED),
        "推广产品",
        "线上",
        true,
        SUBMITTED_AT);
  }

  private static void check(boolean condition, String label) {
    if (!condition) {
      throw new AssertionError(label);
    }
  }

  private static final class RecordingStore implements PromotionApplicationRejectionStore {

    private final List<String> calls = new ArrayList<>();
    private final PromotionApplication application;
    private PromotionApplication saved;

    private RecordingStore(PromotionApplication application) {
      this.application = application;
    }

    @Override
    public PromotionApplication load(String applicationId) {
      calls.add("load");
      check(application.id().equals(applicationId), "application id is loaded");
      return application;
    }

    @Override
    public void save(PromotionApplication application) {
      calls.add("save");
      saved = application;
    }
  }
}
