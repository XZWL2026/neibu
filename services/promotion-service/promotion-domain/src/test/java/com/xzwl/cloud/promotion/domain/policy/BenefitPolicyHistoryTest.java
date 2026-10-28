package com.xzwl.cloud.promotion.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xzwl.cloud.promotion.domain.money.Rate6;
import java.time.Instant;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BenefitPolicyHistoryTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant T1 = Instant.parse("2026-02-01T00:00:00Z");
  private static final Instant T2 = Instant.parse("2026-03-01T00:00:00Z");

  @Test
  void resolvesTheLastPolicyEffectiveAtTheOriginalOrderTime() {
    BenefitPolicyVersion v1 = version("policy-a", 1, T0, "0.010000");
    BenefitPolicyVersion v2 = version("policy-a", 2, T1, "0.020000");
    BenefitPolicyHistory history = BenefitPolicyHistory.rehydrate(List.of(v1, v2));

    assertThat(history.effectiveAt(T0.minusNanos(1))).isEmpty();
    assertThat(history.effectiveAt(T0)).containsSame(v1);
    assertThat(history.effectiveAt(T1.minusNanos(1))).containsSame(v1);
    assertThat(history.effectiveAt(T1)).containsSame(v2);
    assertThat(history.effectiveAt(T2)).containsSame(v2);
  }

  @Test
  void appendIsFunctionalSoOldHistoriesAndFailedAppendsRemainUnchanged() {
    BenefitPolicyVersion v1 = version("policy-a", 1, T0, "0.010000");
    BenefitPolicyHistory original = BenefitPolicyHistory.rehydrate(List.of(v1));

    BenefitPolicyHistory appended = original.append(version("policy-a", 2, T1, "0.020000"));

    assertThat(original.versions()).containsExactly(v1);
    assertThat(appended.versions()).hasSize(2);
    assertThatThrownBy(() -> original.append(version("policy-a", 3, T2, "0.030000")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(original.versions()).containsExactly(v1);
  }

  @Test
  void rehydrateRequiresOneLogicalHistoryWithContinuousVersionsAndIncreasingTime() {
    BenefitPolicyVersion a1 = version("policy-a", 1, T0, "0.010000");

    assertThatThrownBy(() -> BenefitPolicyHistory.rehydrate(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                BenefitPolicyHistory.rehydrate(
                    List.of(a1, version("policy-b", 2, T1, "0.020000"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                BenefitPolicyHistory.rehydrate(
                    List.of(a1, version("policy-a", 3, T1, "0.020000"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                BenefitPolicyHistory.rehydrate(
                    List.of(a1, version("policy-a", 2, T0, "0.020000"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                BenefitPolicyHistory.rehydrate(
                    List.of(a1, version("policy-a", 2, T0.minusSeconds(1), "0.020000"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void appendRejectsDuplicateJumpedSameTimeAndBackwardVersions() {
    BenefitPolicyHistory history =
        BenefitPolicyHistory.rehydrate(List.of(version("policy-a", 1, T0, "0.010000")));

    assertThatThrownBy(() -> history.append(version("policy-a", 1, T1, "0.020000")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> history.append(version("policy-a", 3, T1, "0.020000")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> history.append(version("policy-a", 2, T0, "0.020000")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> history.append(version("policy-a", 2, T0.minusSeconds(1), "0.020000")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void historyDefensivelyCopiesAndExposesAnUnmodifiableList() {
    BenefitPolicyVersion v1 = version("policy-a", 1, T0, "0.010000");
    List<BenefitPolicyVersion> source = new ArrayList<>();
    source.add(v1);
    BenefitPolicyHistory history = BenefitPolicyHistory.rehydrate(source);

    source.clear();

    assertThat(history.versions()).containsExactly(v1);
    assertThatThrownBy(() -> history.versions().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rehydrateReadsAnExternalSourceOnlyOnceAndKeepsItsPrivateSnapshot() {
    BenefitPolicyVersion v1 = version("policy-a", 1, T0, "0.010000");
    BenefitPolicyVersion v2 = version("policy-a", 2, T1, "0.020000");
    BenefitPolicyVersion v3 = version("policy-a", 3, T2, "0.030000");
    List<BenefitPolicyVersion> mutableBacking = new ArrayList<>(List.of(v1, v2));
    ReadCountingVersionList externalSource = new ReadCountingVersionList(mutableBacking);

    BenefitPolicyHistory history = BenefitPolicyHistory.rehydrate(externalSource);

    assertThat(externalSource.readOperations()).isOne();

    mutableBacking.set(1, v3);

    assertThat(history.versions()).containsExactly(v1, v2);
    assertThat(history.effectiveAt(T2)).containsSame(v2);
  }

  @Test
  void nullOrderTimeIsRejected() {
    BenefitPolicyHistory history =
        BenefitPolicyHistory.rehydrate(List.of(version("policy-a", 1, T0, "0.010000")));

    assertThatThrownBy(() -> history.effectiveAt(null)).isInstanceOf(IllegalArgumentException.class);
  }

  private static BenefitPolicyVersion version(
      String historyId, long versionNo, Instant effectiveFrom, String pointRate) {
    return new BenefitPolicyVersion(
        historyId,
        versionNo,
        effectiveFrom,
        Rate6.parse(pointRate),
        Rate6.parse("0.030000"),
        Rate6.parse("0.010000"),
        Map.of());
  }

  private static final class ReadCountingVersionList extends AbstractList<BenefitPolicyVersion> {

    private final List<BenefitPolicyVersion> backing;
    private int readOperations;

    private ReadCountingVersionList(List<BenefitPolicyVersion> backing) {
      this.backing = backing;
    }

    @Override
    public BenefitPolicyVersion get(int index) {
      recordRead();
      return backing.get(index);
    }

    @Override
    public int size() {
      recordRead();
      return backing.size();
    }

    @Override
    public Iterator<BenefitPolicyVersion> iterator() {
      recordRead();
      return List.copyOf(backing).iterator();
    }

    @Override
    public Object[] toArray() {
      recordRead();
      return backing.toArray();
    }

    @Override
    public <T> T[] toArray(T[] array) {
      recordRead();
      return backing.toArray(array);
    }

    private int readOperations() {
      return readOperations;
    }

    private void recordRead() {
      readOperations++;
    }
  }
}
