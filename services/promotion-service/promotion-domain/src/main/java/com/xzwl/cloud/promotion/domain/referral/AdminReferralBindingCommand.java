package com.xzwl.cloud.promotion.domain.referral;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

public record AdminReferralBindingCommand(
    String commandId,
    DirectInviterFacts directInviter,
    ReferralLineage lineage,
    ReferralWritePrecondition writePrecondition,
    String adminId,
    String reason,
    Instant effectiveFrom) {

  public AdminReferralBindingCommand {
    commandId = requireNonBlank(commandId, "commandId");
    directInviter = Objects.requireNonNull(directInviter, "directInviter");
    lineage = Objects.requireNonNull(lineage, "lineage");
    writePrecondition = Objects.requireNonNull(writePrecondition, "writePrecondition");
    if (lineage.graphRevision() != writePrecondition.expectedGraphRevision()) {
      throw new IllegalArgumentException(
          "lineage graphRevision must equal the expectedGraphRevision");
    }
    adminId = requireNonBlank(adminId, "adminId");
    reason = requireNonBlank(reason, "reason");
    effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
  }

  public String semanticFingerprint() {
    MessageDigest digest = sha256();
    update(digest, commandId);
    update(digest, directInviter.inviterUserId());
    update(digest, Boolean.toString(directInviter.approvedPromoter()));
    BenefitSnapshot existing = directInviter.existingBenefitSnapshot();
    update(digest, existing == null ? null : existing.pointBeneficiaryUserId());
    update(digest, existing == null ? null : existing.cashPromoterUserId());
    update(digest, existing == null ? null : existing.cashTier().name());
    update(digest, lineage.directInviterUserId());
    lineage.ancestorUserIds().stream().sorted().forEach(value -> update(digest, value));
    update(digest, Long.toString(lineage.graphRevision()));
    update(digest, Long.toString(writePrecondition.expectedAggregateVersionNo()));
    update(digest, Long.toString(writePrecondition.expectedGraphRevision()));
    update(digest, adminId);
    update(digest, reason);
    update(digest, effectiveFrom.toString());
    return HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void update(MessageDigest digest, String value) {
    if (value == null) {
      digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
      return;
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  @Override
  public String toString() {
    return "AdminReferralBindingCommand[effectiveFrom=%s]".formatted(effectiveFrom);
  }
}
