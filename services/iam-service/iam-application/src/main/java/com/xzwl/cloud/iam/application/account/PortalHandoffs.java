package com.xzwl.cloud.iam.application.account;

public interface PortalHandoffs {
  String issue(AuthPrincipal principal, String sourceSession, String challenge);
  AuthPrincipal redeem(String ticket, String verifier);
}
