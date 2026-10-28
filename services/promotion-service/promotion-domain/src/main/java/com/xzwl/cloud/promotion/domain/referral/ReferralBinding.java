package com.xzwl.cloud.promotion.domain.referral;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Versioned referral binding aggregate.
 *
 * <p>Synchronization protects only this in-memory instance. Cross-instance and recommendation
 * graph concurrency must be enforced by the repository CAS contract documented on
 * {@link ReferralWritePrecondition}.
 */
public final class ReferralBinding {

  private final String referredUserId;
  private final List<ReferralBindingVersion> versions;
  private final Map<String, ProcessedAdminCommand> processedAdminCommands;

  private ReferralBinding(String referredUserId) {
    this.referredUserId = requireNonBlank(referredUserId, "referredUserId");
    this.versions = new ArrayList<>();
    this.processedAdminCommands = new HashMap<>();
  }

  public static ReferralBinding unbound(String referredUserId) {
    return new ReferralBinding(referredUserId);
  }

  public static ReferralBinding bindAtRegistration(
      String referredUserId,
      DirectInviterFacts directInviter,
      ReferralLineage lineage,
      ReferralWritePrecondition writePrecondition,
      Instant effectiveFrom) {
    ReferralBinding binding = new ReferralBinding(referredUserId);
    DirectInviterFacts facts = Objects.requireNonNull(directInviter, "directInviter");
    ReferralLineage validatedLineage = Objects.requireNonNull(lineage, "lineage");
    ReferralWritePrecondition validatedPrecondition =
        Objects.requireNonNull(writePrecondition, "writePrecondition");
    Instant validatedEffectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    if (validatedPrecondition.expectedAggregateVersionNo() != 0) {
      throw new IllegalArgumentException(
          "registration expectedAggregateVersionNo must be 0");
    }
    validateGraphRevision(validatedLineage, validatedPrecondition);
    binding.validateLineage(facts, validatedLineage);
    BenefitSnapshot snapshot = BenefitSnapshotResolver.resolve(facts);
    binding.versions.add(new ReferralBindingVersion(
        1,
        binding.referredUserId,
        facts.inviterUserId(),
        snapshot,
        validatedEffectiveFrom,
        ReferralBindingSource.REGISTRATION,
        validatedPrecondition,
        null,
        null,
        null,
        null));
    return binding;
  }

  public static ReferralBinding rehydrate(List<ReferralBindingVersion> persistedVersions) {
    List<ReferralBindingVersion> history = List.copyOf(
        Objects.requireNonNull(persistedVersions, "persistedVersions"));
    if (history.isEmpty()) {
      throw new IllegalArgumentException("persistedVersions must not be empty");
    }

    ReferralBindingVersion first = Objects.requireNonNull(history.getFirst(), "version");
    ReferralBinding binding = new ReferralBinding(first.referredUserId());
    Set<String> commandIds = new HashSet<>();
    Instant previousEffectiveFrom = null;
    Long previousGraphRevision = null;
    for (int index = 0; index < history.size(); index++) {
      ReferralBindingVersion version = Objects.requireNonNull(history.get(index), "version");
      binding.validatePersistedVersion(
          version, index + 1L, previousEffectiveFrom, previousGraphRevision, commandIds);
      binding.versions.add(version);
      version.adminCommandId().ifPresent(commandId -> binding.processedAdminCommands.put(
          commandId,
          new ProcessedAdminCommand(version.adminSemanticFingerprint(), version)));
      previousEffectiveFrom = version.effectiveFrom();
      previousGraphRevision = version.writePrecondition().expectedGraphRevision();
    }
    return binding;
  }

  public synchronized ReferralBindingChangeResult adminBind(
      AdminReferralBindingCommand command) {
    AdminReferralBindingCommand validated = Objects.requireNonNull(command, "command");
    ReferralBindingChangeResult replay = replayResult(validated, ReferralBindingSource.ADMIN_BIND);
    if (replay != null) {
      return replay;
    }
    if (!versions.isEmpty()) {
      throw new IllegalStateException("administrator initial binding requires an unbound aggregate");
    }
    validateExpectedAggregateVersion(validated);
    return appendAdministratorVersion(validated, ReferralBindingSource.ADMIN_BIND);
  }

  public synchronized ReferralBindingChangeResult adminRebind(
      AdminReferralBindingCommand command) {
    AdminReferralBindingCommand validated = Objects.requireNonNull(command, "command");
    ReferralBindingChangeResult replay = replayResult(validated, ReferralBindingSource.ADMIN_REBIND);
    if (replay != null) {
      return replay;
    }
    if (versions.isEmpty()) {
      throw new IllegalStateException("administrator rebind requires an existing binding");
    }
    validateExpectedAggregateVersion(validated);
    if (validated.effectiveFrom().isBefore(current().effectiveFrom())) {
      throw new IllegalArgumentException("effectiveFrom must not precede the current version");
    }
    return appendAdministratorVersion(validated, ReferralBindingSource.ADMIN_REBIND);
  }

  public String referredUserId() {
    return referredUserId;
  }

  public synchronized ReferralBindingVersion current() {
    if (versions.isEmpty()) {
      throw new IllegalStateException("referral binding has no versions");
    }
    return versions.getLast();
  }

  public synchronized List<ReferralBindingVersion> versions() {
    return List.copyOf(versions);
  }

  private ReferralBindingChangeResult appendAdministratorVersion(
      AdminReferralBindingCommand command,
      ReferralBindingSource source) {
    if (!versions.isEmpty()
        && command.writePrecondition().expectedGraphRevision()
            <= current().writePrecondition().expectedGraphRevision()) {
      throw new IllegalArgumentException(
          "expectedGraphRevision must be greater than the current graphRevision");
    }
    validateLineage(command.directInviter(), command.lineage());
    BenefitSnapshot snapshot = BenefitSnapshotResolver.resolve(command.directInviter());
    ReferralBindingVersion next = new ReferralBindingVersion(
        versions.size() + 1L,
        referredUserId,
        command.directInviter().inviterUserId(),
        snapshot,
        command.effectiveFrom(),
        source,
        command.writePrecondition(),
        command.commandId(),
        command.semanticFingerprint(),
        command.adminId(),
        command.reason());
    versions.add(next);
    processedAdminCommands.put(
        command.commandId(), new ProcessedAdminCommand(command.semanticFingerprint(), next));
    return new ReferralBindingChangeResult(true, next);
  }

  private void validateExpectedAggregateVersion(AdminReferralBindingCommand command) {
    long currentVersionNo = versions.isEmpty() ? 0 : current().versionNo();
    if (command.writePrecondition().expectedAggregateVersionNo() != currentVersionNo) {
      throw new IllegalArgumentException(
          "expectedAggregateVersionNo does not match the current aggregate version");
    }
  }

  private static void validateGraphRevision(
      ReferralLineage lineage,
      ReferralWritePrecondition writePrecondition) {
    if (lineage.graphRevision() != writePrecondition.expectedGraphRevision()) {
      throw new IllegalArgumentException(
          "lineage graphRevision must equal the expectedGraphRevision");
    }
  }

  private ReferralBindingChangeResult replayResult(
      AdminReferralBindingCommand command,
      ReferralBindingSource expectedSource) {
    ProcessedAdminCommand processed = processedAdminCommands.get(command.commandId());
    if (processed == null) {
      return null;
    }
    if (processed.version().source() != expectedSource) {
      throw new IllegalArgumentException(
          "commandId was already used for a different administrator action");
    }
    if (!processed.semanticFingerprint().equals(command.semanticFingerprint())) {
      throw new IllegalArgumentException("commandId was already used for different semantics");
    }
    return new ReferralBindingChangeResult(false, processed.version());
  }

  private void validatePersistedVersion(
      ReferralBindingVersion version,
      long expectedVersionNo,
      Instant previousEffectiveFrom,
      Long previousGraphRevision,
      Set<String> commandIds) {
    if (!referredUserId.equals(version.referredUserId())) {
      throw new IllegalArgumentException("all persisted versions must have the same referredUserId");
    }
    if (version.versionNo() != expectedVersionNo) {
      throw new IllegalArgumentException("persisted versionNo values must be continuous from 1");
    }
    if (expectedVersionNo == 1) {
      if (version.source() != ReferralBindingSource.REGISTRATION
          && version.source() != ReferralBindingSource.ADMIN_BIND) {
        throw new IllegalArgumentException("first persisted version has an invalid source");
      }
    } else if (version.source() != ReferralBindingSource.ADMIN_REBIND) {
      throw new IllegalArgumentException("later persisted versions must be ADMIN_REBIND");
    }
    if (previousEffectiveFrom != null
        && version.effectiveFrom().isBefore(previousEffectiveFrom)) {
      throw new IllegalArgumentException("persisted effectiveFrom values must not decrease");
    }
    if (previousGraphRevision != null
        && version.writePrecondition().expectedGraphRevision() <= previousGraphRevision) {
      throw new IllegalArgumentException(
          "persisted graphRevision values must strictly increase");
    }
    version.adminCommandId().ifPresent(commandId -> {
      if (!commandIds.add(commandId)) {
        throw new IllegalArgumentException("persisted administrator commandId values must be unique");
      }
    });
  }

  private void validateLineage(DirectInviterFacts directInviter, ReferralLineage lineage) {
    if (!directInviter.inviterUserId().equals(lineage.directInviterUserId())) {
      throw new IllegalArgumentException("direct inviter facts must match lineage");
    }
    if (lineage.contains(referredUserId)) {
      throw new IllegalArgumentException("referral cycle is not allowed");
    }
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  @Override
  public synchronized String toString() {
    return "ReferralBinding[versionCount=%d]".formatted(versions.size());
  }

  private record ProcessedAdminCommand(
      String semanticFingerprint,
      ReferralBindingVersion version) {
  }
}
