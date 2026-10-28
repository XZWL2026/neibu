package com.xzwl.cloud.billing.domain.settlement;

import com.xzwl.cloud.billing.domain.funding.ConsumptionAllocation;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class TaskSettlement {

  private final String taskId;
  private final String walletId;
  private final List<SettlementAllocation> allocations;
  private final TaskSettlementStatus status;
  private final long version;
  private final String terminalCommandId;
  private final String settlementId;
  private final Map<String, Decimal4> finalSuccessfulPoints4;
  private final List<SuccessfulAllocationFact> successfulFacts;
  private final Decimal4 totalSuccessfulPoints4;

  private TaskSettlement(
      String taskId,
      String walletId,
      List<SettlementAllocation> allocations,
      TaskSettlementStatus status,
      long version,
      String terminalCommandId,
      String settlementId,
      Map<String, Decimal4> finalSuccessfulPoints4,
      List<SuccessfulAllocationFact> successfulFacts,
      Decimal4 totalSuccessfulPoints4) {
    this.taskId = taskId;
    this.walletId = walletId;
    this.allocations = List.copyOf(allocations);
    this.status = status;
    this.version = version;
    this.terminalCommandId = terminalCommandId;
    this.settlementId = settlementId;
    this.finalSuccessfulPoints4 = Map.copyOf(finalSuccessfulPoints4);
    this.successfulFacts = List.copyOf(successfulFacts);
    this.totalSuccessfulPoints4 = totalSuccessfulPoints4;
  }

  public static TaskSettlement pending(
      String taskId, String walletId, List<SettlementAllocation> allocations, long version) {
    String validatedTaskId = requireId(taskId, "taskId");
    String validatedWalletId = requireId(walletId, "walletId");
    List<SettlementAllocation> validatedAllocations =
        List.copyOf(Objects.requireNonNull(allocations, "allocations"));
    Set<String> allocationIds = new HashSet<>();
    for (SettlementAllocation settlementAllocation : validatedAllocations) {
      Objects.requireNonNull(settlementAllocation, "settlementAllocation");
      if (!allocationIds.add(settlementAllocation.allocationId())) {
        throw new IllegalArgumentException("duplicate settlement allocation id");
      }
      if (!validatedWalletId.equals(settlementAllocation.allocation().walletId())) {
        throw new IllegalArgumentException("all allocations must belong to the settlement wallet");
      }
    }
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
    return new TaskSettlement(
        validatedTaskId,
        validatedWalletId,
        validatedAllocations,
        TaskSettlementStatus.PENDING,
        version,
        null,
        null,
        Map.of(),
        List.of(),
        Decimal4.zero());
  }

  public TaskSettlement observeProcessing() {
    return this;
  }

  public TaskSettlement fail(String commandId, long expectedVersion) {
    String validatedCommandId = requireId(commandId, "commandId");
    if (status != TaskSettlementStatus.PENDING) {
      if (status == TaskSettlementStatus.FAILED
          && terminalCommandId.equals(validatedCommandId)) {
        requireReplayVersion(expectedVersion);
        return this;
      }
      throw new IllegalStateException("terminal task settlement cannot be overwritten");
    }
    requireExpectedVersion(expectedVersion);
    return new TaskSettlement(
        taskId,
        walletId,
        allocations,
        TaskSettlementStatus.FAILED,
        nextVersion(),
        validatedCommandId,
        null,
        Map.of(),
        List.of(),
        Decimal4.zero());
  }

  public TaskSettlement succeed(
      String commandId,
      String settlementId,
      long expectedVersion,
      List<SuccessfulAllocationInput> successfulAllocationInputs) {
    String validatedCommandId = requireId(commandId, "commandId");
    String validatedSettlementId = requireId(settlementId, "settlementId");
    if (status == TaskSettlementStatus.FAILED) {
      throw new IllegalStateException("terminal task settlement cannot be overwritten");
    }
    Map<String, Decimal4> validatedAmounts =
        validateSuccessfulAmounts(successfulAllocationInputs);
    if (status == TaskSettlementStatus.SUCCEEDED) {
      if (terminalCommandId.equals(validatedCommandId)
          && this.settlementId.equals(validatedSettlementId)
          && finalSuccessfulPoints4.equals(validatedAmounts)) {
        requireReplayVersion(expectedVersion);
        return this;
      }
      throw new IllegalStateException("terminal task settlement cannot be overwritten");
    }
    requireExpectedVersion(expectedVersion);

    List<SuccessfulAllocationFact> facts = new ArrayList<>();
    Decimal4 total = Decimal4.zero();
    Decimal4 allocatedTotal = Decimal4.zero();
    for (SettlementAllocation settlementAllocation : allocations) {
      ConsumptionAllocation allocation = settlementAllocation.allocation();
      Decimal4 successful = validatedAmounts.get(settlementAllocation.allocationId());
      allocatedTotal = allocatedTotal.add(allocation.allocatedPoints4());
      total = total.add(successful);
      if (successful.isPositive()) {
        facts.add(
            new SuccessfulAllocationFact(
                validatedSettlementId,
                taskId,
                settlementAllocation.allocationId(),
                allocation.lotId(),
                walletId,
                allocation.lotType(),
                allocation.benefitEligible(),
                successful,
                allocation.benefitEligible()));
      }
    }
    if (total.compareTo(allocatedTotal) > 0) {
      throw new IllegalArgumentException("total successful points cannot exceed allocations");
    }
    return new TaskSettlement(
        taskId,
        walletId,
        allocations,
        TaskSettlementStatus.SUCCEEDED,
        nextVersion(),
        validatedCommandId,
        validatedSettlementId,
        validatedAmounts,
        facts,
        total);
  }

  public String taskId() {
    return taskId;
  }

  public String walletId() {
    return walletId;
  }

  public List<SettlementAllocation> allocations() {
    return allocations;
  }

  public TaskSettlementStatus status() {
    return status;
  }

  public long version() {
    return version;
  }

  public Optional<String> terminalCommandId() {
    return Optional.ofNullable(terminalCommandId);
  }

  public Optional<String> settlementId() {
    return Optional.ofNullable(settlementId);
  }

  public List<SuccessfulAllocationFact> successfulFacts() {
    return successfulFacts;
  }

  public Decimal4 totalSuccessfulPoints4() {
    return totalSuccessfulPoints4;
  }

  private Map<String, Decimal4> validateSuccessfulAmounts(
      List<SuccessfulAllocationInput> successfulAllocationInputs) {
    Objects.requireNonNull(successfulAllocationInputs, "successfulAllocationInputs");
    Map<String, Decimal4> validated = new LinkedHashMap<>();
    Set<String> expectedAllocationIds = new HashSet<>();
    for (SettlementAllocation settlementAllocation : allocations) {
      expectedAllocationIds.add(settlementAllocation.allocationId());
    }
    for (SuccessfulAllocationInput input : successfulAllocationInputs) {
      Objects.requireNonNull(input, "successfulAllocationInput");
      String allocationId = requireId(input.allocationId(), "allocationId");
      Decimal4 successful =
          Objects.requireNonNull(input.finalSuccessfulPoints4(), "finalSuccessfulPoints4");
      if (!expectedAllocationIds.contains(allocationId)) {
        throw new IllegalArgumentException("successful points contain an unknown allocation");
      }
      if (validated.putIfAbsent(allocationId, successful) != null) {
        throw new IllegalArgumentException("duplicate successful allocation input");
      }
    }
    if (validated.size() != allocations.size()) {
      throw new IllegalArgumentException("successful points must cover every allocation");
    }
    for (SettlementAllocation settlementAllocation : allocations) {
      Decimal4 successful = validated.get(settlementAllocation.allocationId());
      if (successful.isNegative()
          || successful.compareTo(settlementAllocation.allocation().allocatedPoints4()) > 0) {
        throw new IllegalArgumentException(
            "successful points must be between zero and allocated points");
      }
    }
    return Map.copyOf(validated);
  }

  private void requireExpectedVersion(long expectedVersion) {
    if (expectedVersion != version) {
      throw new IllegalStateException("expectedVersion does not match task settlement version");
    }
  }

  private void requireReplayVersion(long expectedVersion) {
    if (expectedVersion != version - 1) {
      throw new IllegalStateException("expectedVersion does not match the original terminal command");
    }
  }

  private long nextVersion() {
    if (version == Long.MAX_VALUE) {
      throw new IllegalStateException("task settlement version cannot advance");
    }
    return version + 1;
  }

  static String requireId(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
