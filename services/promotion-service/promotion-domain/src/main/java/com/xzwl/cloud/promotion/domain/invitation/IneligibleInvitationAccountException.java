package com.xzwl.cloud.promotion.domain.invitation;

public final class IneligibleInvitationAccountException extends IllegalArgumentException {

  IneligibleInvitationAccountException() {
    super("account is not eligible for an invitation profile");
  }
}
