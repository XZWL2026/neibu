package com.xzwl.cloud.iam.application.account;

/** Server-side eligibility of an existing business identity, never a client role claim. */
public interface PortalDirectory {
  boolean eligible(String role, String userId);
  default boolean apiEntryEligible(String userId) { return eligible("API_USER", userId); }
}
