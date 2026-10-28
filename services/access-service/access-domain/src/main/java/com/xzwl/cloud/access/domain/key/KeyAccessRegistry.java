package com.xzwl.cloud.access.domain.key;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** In-memory domain registry for key bindings and most-recent-valid fallback selection. */
public final class KeyAccessRegistry {

  private final Map<String, RegisteredKey> keys = new LinkedHashMap<>();
  private final Map<String, Map<String, Instant>> bindingsByAccount = new HashMap<>();
  private final Map<String, String> selectedKeyByAccount = new HashMap<>();
  private final Map<String, Boolean> accountEnabled = new HashMap<>();

  public synchronized void register(PrimaryKey key, Instant registeredAt) {
    PrimaryKey requested = Objects.requireNonNull(key, "key");
    Instant timestamp = Objects.requireNonNull(registeredAt, "registeredAt");
    if (keys.containsKey(requested.keyId())) {
      throw new IllegalStateException("key id already exists");
    }
    keys.put(requested.keyId(), new RegisteredKey(requested));
    accountEnabled.putIfAbsent(requested.accountId(), true);
    bindInternal(requested.accountId(), requested.keyId(), timestamp);
  }

  public synchronized void bind(String accountId, String keyId, Instant boundAt) {
    String account = requireId(accountId, "accountId");
    String key = requireId(keyId, "keyId");
    Instant timestamp = Objects.requireNonNull(boundAt, "boundAt");
    RegisteredKey registered = requireKey(key);
    if (!registered.enabled) {
      throw new IllegalStateException("disabled key cannot be bound");
    }
    accountEnabled.putIfAbsent(account, true);
    bindInternal(account, key, timestamp);
  }

  public synchronized void unbind(String accountId, String keyId) {
    String account = requireId(accountId, "accountId");
    String key = requireId(keyId, "keyId");
    Map<String, Instant> bindings = bindingsByAccount.get(account);
    if (bindings == null || bindings.remove(key) == null) {
      throw new IllegalStateException("account is not bound to the key");
    }
    if (key.equals(selectedKeyByAccount.get(account))) {
      selectedKeyByAccount.remove(account);
    }
    if (bindings.isEmpty()) {
      bindingsByAccount.remove(account);
    }
  }

  public synchronized void disable(String keyId) {
    requireKey(requireId(keyId, "keyId")).enabled = false;
  }

  public synchronized void enable(String keyId) {
    requireKey(requireId(keyId, "keyId")).enabled = true;
  }

  public synchronized void disableAccount(String accountId) {
    accountEnabled.put(requireId(accountId, "accountId"), false);
  }

  public synchronized void enableAccount(String accountId) {
    accountEnabled.put(requireId(accountId, "accountId"), true);
  }

  public synchronized Optional<PrimaryKey> currentKey(String accountId) {
    String account = requireId(accountId, "accountId");
    if (!accountEnabled.getOrDefault(account, true)) {
      return Optional.empty();
    }
    Map<String, Instant> bindings = bindingsByAccount.get(account);
    if (bindings == null || bindings.isEmpty()) {
      return Optional.empty();
    }
    String selected = selectedKeyByAccount.get(account);
    if (selected != null && isEnabledBinding(bindings, selected)) {
      return Optional.of(requireKey(selected).key);
    }
    return bindings.entrySet().stream()
        .filter(entry -> isEnabledBinding(bindings, entry.getKey()))
        .max(Map.Entry.comparingByValue(Comparator.naturalOrder()))
        .map(entry -> requireKey(entry.getKey()).key);
  }

  public synchronized boolean canGenerate(String accountId, String keyId) {
    String account = requireId(accountId, "accountId");
    String key = requireId(keyId, "keyId");
    return canView(account, key)
        && currentKey(account).filter(primary -> primary.keyId().equals(key))
        .map(primary -> primary.type() == KeyType.MEDIA && requireKey(key).enabled)
        .orElse(false);
  }

  public synchronized boolean canView(String accountId, String keyId) {
    String account = requireId(accountId, "accountId");
    String key = requireId(keyId, "keyId");
    Map<String, Instant> bindings = bindingsByAccount.get(account);
    return accountEnabled.getOrDefault(account, true)
        && bindings != null
        && bindings.containsKey(key)
        && requireKey(key).enabled;
  }

  private void bindInternal(String accountId, String keyId, Instant boundAt) {
    bindingsByAccount.computeIfAbsent(accountId, ignored -> new HashMap<>()).put(keyId, boundAt);
    selectedKeyByAccount.put(accountId, keyId);
  }

  private boolean isEnabledBinding(Map<String, Instant> bindings, String keyId) {
    return bindings.containsKey(keyId) && requireKey(keyId).enabled;
  }

  private RegisteredKey requireKey(String keyId) {
    RegisteredKey key = keys.get(keyId);
    if (key == null) {
      throw new IllegalArgumentException("unknown key");
    }
    return key;
  }

  private static String requireId(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static final class RegisteredKey {
    private final PrimaryKey key;
    private boolean enabled = true;

    private RegisteredKey(PrimaryKey key) {
      this.key = key;
    }
  }
}
