package com.xzwl.cloud.access.domain.key;

import java.time.Instant;

public final class KeyAccessRegistryTest {

  @org.junit.jupiter.api.Test
  void runsExistingBehaviorChecks() { main(new String[0]); }

  public static void main(String[] args) {
    fallsBackToTheMostRecentEnabledBindingAndRestoresSelection();
    System.out.println("KeyAccessRegistryTest passed: 1");
  }

  private static void fallsBackToTheMostRecentEnabledBindingAndRestoresSelection() {
    KeyAccessRegistry registry = new KeyAccessRegistry();
    registry.register(
        new PrimaryKey("key-1", "owner-1", KeyType.MEDIA,
            "sk-_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
        Instant.parse("2026-09-05T00:00:00Z"));
    registry.register(
        new PrimaryKey("key-2", "owner-2", KeyType.MEDIA,
            "sk-_abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"),
        Instant.parse("2026-09-05T00:01:00Z"));

    registry.bind("viewer-1", "key-1", Instant.parse("2026-09-05T00:02:00Z"));
    registry.bind("viewer-1", "key-2", Instant.parse("2026-09-05T00:03:00Z"));
    check(registry.currentKey("viewer-1").orElseThrow().keyId().equals("key-2"),
        "latest binding is selected");
    check(registry.canGenerate("viewer-1", "key-2"), "enabled media key can generate");

    registry.disable("key-2");
    check(registry.currentKey("viewer-1").orElseThrow().keyId().equals("key-1"),
        "disabled selected key falls back to the previous binding");
    check(!registry.canGenerate("viewer-1", "key-2"), "disabled key cannot generate");

    registry.enable("key-2");
    check(registry.currentKey("viewer-1").orElseThrow().keyId().equals("key-2"),
        "re-enabled selected key is restored");

    registry.unbind("viewer-1", "key-2");
    check(registry.currentKey("viewer-1").orElseThrow().keyId().equals("key-1"),
        "exit removes the binding and falls back");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }
}
