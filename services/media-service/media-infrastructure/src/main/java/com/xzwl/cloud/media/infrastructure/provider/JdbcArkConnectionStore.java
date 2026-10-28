package com.xzwl.cloud.media.infrastructure.provider;

import com.xzwl.cloud.media.application.provider.ArkConnectionStore;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Provider-scoped atomic, versioned plaintext connections, with read-time migration of historical ciphertext. */
public final class JdbcArkConnectionStore implements ArkConnectionStore {
  private static final byte[] CONTEXT = "xzwl:media:ark-connection:1:v1".getBytes(StandardCharsets.UTF_8);
  private final JdbcTemplate jdbc;
  private final String legacyEncryptionKey;
  private final Connection fallback;
  private final Set<String> allowedHosts;

  public JdbcArkConnectionStore(JdbcTemplate jdbc, String encryptionKey, Connection fallback, Set<String> allowedHosts) {
    this.jdbc = jdbc;
    this.fallback = fallback;
    this.allowedHosts = allowedHosts.stream().map(host -> host.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    this.legacyEncryptionKey = encryptionKey;
  }

  @Override public boolean hasConfiguredConnection() {
    var ids = new java.util.HashSet<>(jdbc.queryForList("SELECT provider_id FROM media_provider_connection", String.class));
    if (!fallback.providerId().isBlank()) ids.add(fallback.providerId());
    for (String id : ids) {
      try {
        Connection connection = load(id);
        if (connection.baseUrl() != null && !connection.baseUrl().isBlank()
            && connection.apiKey() != null && !connection.apiKey().isBlank()) return true;
      } catch (NotEditable unavailable) { /* An unreadable legacy key is not a configured connection. */ }
    }
    return false;
  }

  @Override public boolean editable() { return true; }

  @Override public Connection load(String providerId) {
    validateProviderId(providerId);
    while (true) {
      var rows = jdbc.query("SELECT version,provider_id,base_url,api_key,encrypted_api_key,timeout_seconds,retry_count FROM media_provider_connection WHERE provider_id=?",
          (rs, row) -> new Stored(new Connection(rs.getLong("version"), rs.getString("provider_id"),
              rs.getString("base_url"), rs.getString("api_key"),rs.getInt("timeout_seconds"),rs.getInt("retry_count")), rs.getString("encrypted_api_key")), providerId);
      if (rows.isEmpty()) return providerId.equals(fallback.providerId())
          ? fallback : new Connection(0, providerId, "", "");
      Stored stored = rows.getFirst();
      Connection current = stored.connection();
      if (current.apiKey() != null) return current;
      String plaintext = decrypt(stored.encryptedKey());
      if (jdbc.update("UPDATE media_provider_connection SET api_key=?,encrypted_api_key='' WHERE provider_id=? AND version=? AND api_key IS NULL",
          plaintext, providerId, current.version()) == 1)
        return new Connection(current.version(), current.providerId(), current.baseUrl(), plaintext,current.timeoutSeconds(),current.retryCount());
      // A concurrent migration or save won; return its complete row rather than stale credentials.
    }
  }

  private record Stored(Connection connection, String encryptedKey) {}

  @Override public Connection save(long expectedVersion,String providerId,String baseUrl,String apiKey) {
    return save(expectedVersion,providerId,baseUrl,apiKey,null,null);
  }
  @Override public Connection save(long expectedVersion,String providerId,String baseUrl,String apiKey,Integer timeoutSeconds,Integer retryCount) {
    if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE || providerId == null || !providerId.matches("[A-Za-z0-9._:-]{1,128}"))
      throw new IllegalArgumentException("Invalid Ark connection");
    String normalizedUrl = normalizeUrl(baseUrl);
    Connection current = load(providerId);
    if (current.version() != expectedVersion) throw new VersionConflict();
    String secret = apiKey == null ? "" : apiKey.trim();
    if (secret.isEmpty()) {
      if (!sameOrigin(current.baseUrl(), normalizedUrl)) throw new IllegalArgumentException("A new API key is required for a changed origin");
      secret = current.apiKey();
    }
    if (secret == null || secret.isEmpty() || secret.length() > 4096 || secret.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)))
      throw new IllegalArgumentException("Invalid Ark API key");
    Connection saved = new Connection(expectedVersion + 1, providerId, normalizedUrl, secret,
        timeoutSeconds==null?current.timeoutSeconds():timeoutSeconds,retryCount==null?current.retryCount():retryCount);
    if (expectedVersion == 0) {
      try {
        jdbc.update("INSERT INTO media_provider_connection(version,provider_id,base_url,api_key,encrypted_api_key,timeout_seconds,retry_count) VALUES (?,?,?,?,'',?,?)",
            saved.version(), providerId, normalizedUrl, secret,saved.timeoutSeconds(),saved.retryCount());
      } catch (DuplicateKeyException conflict) { throw new VersionConflict(); }
    } else if (jdbc.update("UPDATE media_provider_connection SET version=?,base_url=?,api_key=?,encrypted_api_key='',timeout_seconds=?,retry_count=? WHERE provider_id=? AND version=?",
        saved.version(), normalizedUrl, secret,saved.timeoutSeconds(),saved.retryCount(), providerId, expectedVersion) != 1) throw new VersionConflict();
    return saved;
  }

  private static void validateProviderId(String providerId) {
    if (providerId == null || !providerId.matches("[A-Za-z0-9._:-]{1,128}"))
      throw new IllegalArgumentException("Invalid provider identifier");
  }

  private String normalizeUrl(String value) {
    try {
      if (value == null || value.length() > 2048) throw new IllegalArgumentException();
      URI uri = ProviderSettings.providerEndpoint(value);
      if (!"zexapi.com".equalsIgnoreCase(uri.getHost()) && !allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT)))
        throw new IllegalArgumentException();
      return uri.toString();
    } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Unsupported provider HTTP/HTTPS endpoint"); }
  }

  private static boolean sameOrigin(String left, String right) {
    try {
      URI a = URI.create(left), b = URI.create(right);
      return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
          && effectivePort(a) == effectivePort(b);
    } catch (IllegalArgumentException | NullPointerException invalid) { return false; }
  }

  private static int effectivePort(URI uri) {
    return uri.getPort() == -1 ? ("http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443) : uri.getPort();
  }

  private String decrypt(String value) {
    if (legacyEncryptionKey == null || legacyEncryptionKey.isBlank()) throw new NotEditable();
    try {
      byte[] key = Base64.getDecoder().decode(legacyEncryptionKey.trim());
      if (key.length != 32) throw new IllegalArgumentException();
      String[] parts = value.split("\\.", -1);
      if (parts.length != 2) throw new IllegalArgumentException();
      byte[] nonce = Base64.getDecoder().decode(parts[0]);
      if (nonce.length != 12) throw new IllegalArgumentException();
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(CONTEXT);
      return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException failure) { throw new NotEditable(); }
  }
}
