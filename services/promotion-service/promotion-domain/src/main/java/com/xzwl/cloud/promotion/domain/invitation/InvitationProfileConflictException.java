package com.xzwl.cloud.promotion.domain.invitation;

public final class InvitationProfileConflictException extends IllegalStateException {

  InvitationProfileConflictException() {
    super("invitation profile conflicts with the existing permanent identity");
  }
}
