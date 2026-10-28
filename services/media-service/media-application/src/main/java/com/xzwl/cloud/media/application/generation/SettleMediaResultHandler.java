package com.xzwl.cloud.media.application.generation;

import java.util.Objects;

/** Applies one terminal settlement decision; the billing adapter owns operation idempotency. */
public final class SettleMediaResultHandler {

  private final MediaSettlementPort settlement;

  public SettleMediaResultHandler(MediaSettlementPort settlement) {
    this.settlement = Objects.requireNonNull(settlement, "settlement");
  }

  public void handle(MediaSettlementCommand command) {
    MediaSettlementCommand requested = Objects.requireNonNull(command, "command");
    if (requested.kind() == MediaSettlementCommand.Kind.SUCCEEDED) {
      settlement.settleSuccess(requested.operationId(), requested.chargedAmount());
    } else if (requested.refundAmount().signum() > 0) {
      settlement.refund(requested.operationId(), requested.refundAmount());
    }
  }
}
