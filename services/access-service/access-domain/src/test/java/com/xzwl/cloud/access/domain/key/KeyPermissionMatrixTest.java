package com.xzwl.cloud.access.domain.key;

import java.time.Instant;

public final class KeyPermissionMatrixTest {

  @org.junit.jupiter.api.Test
  void runsExistingBehaviorChecks() { main(new String[0]); }

  public static void main(String[] args) {
    disabledAccountOrKeyCannotViewOrGenerate();
    System.out.println("KeyPermissionMatrixTest passed: 1");
  }

  private static void disabledAccountOrKeyCannotViewOrGenerate() {
    KeyAccessRegistry registry = new KeyAccessRegistry();
    registry.register(
        new PrimaryKey("key-1", "owner-1", KeyType.MEDIA,
            "sk-_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
        Instant.parse("2026-09-05T00:00:00Z"));
    registry.bind("viewer-1", "key-1", Instant.parse("2026-09-05T00:01:00Z"));

    check(registry.canView("viewer-1", "key-1"), "enabled binding is viewable");
    check(registry.canGenerate("viewer-1", "key-1"), "enabled selected media key can generate");
    registry.disableAccount("viewer-1");
    check(!registry.canView("viewer-1", "key-1"), "disabled account cannot view");
    check(!registry.canGenerate("viewer-1", "key-1"), "disabled account cannot generate");
    registry.enableAccount("viewer-1");
    registry.disable("key-1");
    check(!registry.canView("viewer-1", "key-1"), "disabled key cannot view");
    check(!registry.canGenerate("viewer-1", "key-1"), "disabled key cannot generate");
    registry.enable("key-1");
    check(registry.canGenerate("viewer-1", "key-1"), "re-enabled key restores generation");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }
}
