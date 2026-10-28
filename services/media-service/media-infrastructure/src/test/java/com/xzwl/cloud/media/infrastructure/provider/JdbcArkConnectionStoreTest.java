package com.xzwl.cloud.media.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.media.application.provider.ArkConnectionStore;
import com.xzwl.cloud.media.application.provider.ArkConnectionStore.Connection;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcArkConnectionStoreTest {
  static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
  static final String URL = "https://ark.example/api/v3";
  static final String SECRET = "private-provider-key";
  static final Connection FALLBACK = new Connection(0, "ark", URL, "environment-key");
  JdbcTemplate jdbc;
  JdbcArkConnectionStore store;
  @BeforeEach void setup() {
    var ds = new DriverManagerDataSource("jdbc:h2:mem:ark-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    Flyway.configure().dataSource(ds).locations("classpath:db/media-migration/common").load().migrate();
    jdbc = new JdbcTemplate(ds);
    store = open("", FALLBACK);
  }
  JdbcArkConnectionStore open(String key, Connection fallback) {
    return new JdbcArkConnectionStore(jdbc, key, fallback, Set.of("ark.example", "other.example"));
  }
  String encrypted() { return jdbc.queryForObject("SELECT encrypted_api_key FROM media_provider_connection WHERE provider_id='ark'", String.class); }
  String plaintext() { return jdbc.queryForObject("SELECT api_key FROM media_provider_connection WHERE provider_id='ark'", String.class); }
  @Test void requestPolicyPersistsAndOmittedUpdatesRetainIt() {
    assertEquals(180,store.load("ark").timeoutSeconds());
    assertEquals(0,store.load("ark").retryCount());
    var saved=store.save(0,"ark",URL,SECRET,40,2);
    assertEquals(saved,open(null,FALLBACK).load("ark"));
    assertEquals(40,store.save(1,"ark",URL,"",null,null).timeoutSeconds());
    assertEquals(2,store.load("ark").retryCount());
    assertThrows(ArkConnectionStore.VersionConflict.class,()->store.save(1,"ark",URL,"",30,1));
    for(int timeout:new int[]{0,181}) assertThrows(IllegalArgumentException.class,()->store.save(2,"ark",URL,"",timeout,0));
    for(int retry:new int[]{-1,4}) assertThrows(IllegalArgumentException.class,()->store.save(2,"ark",URL,"",180,retry));
    assertEquals(2,store.load("ark").version());
  }
  @Test void savesHttpPortAndPrefixAndKeepsOriginKeyProtection() {
    var proxy = new JdbcArkConnectionStore(jdbc, "", FALLBACK, Set.of("14.103.26.228"));
    String base = "http://14.103.26.228:3000/doubao/api/v3";
    var saved = proxy.save(0, "doubao-proxy", base + "/", SECRET);
    assertEquals(base, saved.baseUrl());
    assertEquals(saved, proxy.load("doubao-proxy"));
    assertEquals(SECRET, proxy.save(1, "doubao-proxy", base, "").apiKey());
    assertThrows(IllegalArgumentException.class, () -> proxy.save(2, "doubao-proxy", base.replace(":3000", ":3001"), ""));
    assertThrows(IllegalArgumentException.class, () -> proxy.save(2, "doubao-proxy", base.replace("http:", "https:"), ""));
    assertEquals("http://14.103.26.228/custom", proxy.save(2, "doubao-proxy", "http://14.103.26.228:80/custom/", "new-key").baseUrl());
    assertEquals("new-key", proxy.save(3, "doubao-proxy", "http://14.103.26.228/custom", "").apiKey());
    assertThrows(IllegalArgumentException.class, () -> proxy.save(4, "doubao-proxy", "http://14.103.26.228:443/custom", ""));
    assertThrows(IllegalArgumentException.class, () -> proxy.save(4, "doubao-proxy", base.replace("14.103.26.228", "127.0.0.1"), SECRET));
    assertEquals("http://14.103.26.228:3000/custom", ProviderSettings.from(java.util.Map.of("MEDIA_ARK_BASE_URL", base), new com.fasterxml.jackson.databind.ObjectMapper())
        .withConnection(new Connection(1, "proxy", "http://14.103.26.228:3000/custom", SECRET)).arkConnection().baseUrl());
    assertEquals("http://14.103.26.228:3000/custom", ProviderSettings.providerEndpoint("http://14.103.26.228:3000/custom/").toString());
  }
  @Test void savesZexWithoutChangingArkOrWeakeningAddressValidation() {
    store.save(0,"ark",URL,SECRET);
    var saved=store.save(0,"image","https://ZEXAPI.com:443/v1/","zex-fixture-key");
    assertEquals("https://zexapi.com/v1",saved.baseUrl());
    assertEquals(saved,open(null,FALLBACK).load("image"));
    assertEquals("zex-fixture-key",store.save(1,"image",saved.baseUrl(),"").apiKey());
    assertEquals(SECRET,store.load("ark").apiKey());
    assertThrows(IllegalArgumentException.class,()->store.save(1,"ark",saved.baseUrl(),""));
    for(String url:java.util.List.of("https://zexapi.com.evil.example/v1",
        "https://zexapi.com/v1?key=value","https://user@zexapi.com/v1",
        "https://127.0.0.1/v1"))
      assertThrows(IllegalArgumentException.class,()->store.save(2,"image",url,"test-key"));
  }
  @Test void providersHaveIndependentVersionsAndCannotInheritAnotherKey() {
    store.save(0, "ark", URL, SECRET);
    assertDoesNotThrow(() -> store.save(0, "image", "https://other.example", "image-test-key"));
    assertThrows(IllegalArgumentException.class, () -> store.save(0, "unconfigured", URL, ""));
    assertEquals("", store.load("unconfigured").apiKey());
    assertEquals(0, store.load("unconfigured").version());
    assertEquals(SECRET, store.load("ark").apiKey());
    assertEquals("image-test-key", open(null,FALLBACK).load("image").apiKey());
    store.save(1,"image","https://other.example", "");
    assertEquals(1,store.load("ark").version());
    assertEquals(2,store.load("image").version());
    assertEquals("image-test-key", store.load("image").apiKey());
    assertEquals("", store.load("ARK").apiKey());
  }
  @Test void flywayMigratesOnlyTheOriginalProviderAndPreservesLegacyCiphertext() throws Exception {
    for (boolean encrypted : new boolean[]{false,true}) {
      var ds = new DriverManagerDataSource("jdbc:h2:mem:migration-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
      Flyway.configure().dataSource(ds).locations("classpath:db/media-migration/common").target("6").load().migrate();
      var old = new JdbcTemplate(ds);
      old.update("INSERT INTO media_ark_connection(id,version,provider_id,base_url,api_key,encrypted_api_key) VALUES(1,7,'original',?,?,?)",
          URL, encrypted ? null : SECRET, encrypted ? legacyCiphertext() : "");
      Flyway.configure().dataSource(ds).locations("classpath:db/media-migration/common").load().migrate();
      var migrated = new JdbcArkConnectionStore(old,KEY,new Connection(0,"original",URL,"environment-key"),Set.of("ark.example"));
      assertEquals(new Connection(7,"original",URL,SECRET),migrated.load("original"));
      assertEquals(new Connection(0,"image","",""),migrated.load("image"));
      assertEquals(1,old.queryForObject("SELECT COUNT(*) FROM media_provider_connection",Integer.class));
    }
  }
  @Test void savesPlaintextWithoutAMasterAndLoadsAfterRestartWithOptimisticVersion() {
    assertTrue(store.editable()); assertEquals(FALLBACK, store.load("ark"));
    var saved = store.save(0, "ark", "https://ARK.example:443/api/v3/", SECRET);
    assertEquals(new Connection(1, "ark", URL, SECRET), saved);
    assertEquals(SECRET, plaintext()); assertEquals("", encrypted());
    assertEquals(saved, open(null, FALLBACK).load("ark"));
    assertEquals(saved, open("unused-invalid-legacy-master", FALLBACK).load("ark"));
    assertEquals(2, store.save(1, "ark", URL, "rotated-key").version());
    assertEquals("rotated-key", plaintext()); assertEquals("", encrypted());
    assertThrows(ArkConnectionStore.VersionConflict.class, () -> store.save(1, "ark", URL, "new-key"));
    assertEquals("rotated-key", store.load("ark").apiKey());
    assertFalse(saved.toString().contains(SECRET));
  }
  @Test void blankKeyRetainsExistingOnlyForSameOriginAndInitialKeyIsRequired() {
    assertEquals("environment-key", store.save(0, "ark", URL, " ").apiKey());
    assertThrows(IllegalArgumentException.class, () -> store.save(0, "renamed", URL, null));
    assertThrows(IllegalArgumentException.class, () -> store.save(1, "ark", "https://other.example", ""));
    assertEquals("new-key", store.save(1, "ark", "https://other.example", " new-key ").apiKey());
    jdbc.update("DELETE FROM media_provider_connection");
    assertThrows(IllegalArgumentException.class, () -> open(KEY, new Connection(0, "ark", URL, "")).save(0, "ark", URL, null));
  }
  String legacyCiphertext() throws Exception {
    byte[] nonce = new byte[12];
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(new byte[32], "AES"), new GCMParameterSpec(128, nonce));
    cipher.updateAAD("xzwl:media:ark-connection:1:v1".getBytes(StandardCharsets.UTF_8));
    return Base64.getEncoder().encodeToString(nonce) + "." + Base64.getEncoder().encodeToString(cipher.doFinal(SECRET.getBytes(StandardCharsets.UTF_8)));
  }
  void insertLegacy(String ciphertext) {
    jdbc.update("INSERT INTO media_provider_connection(version,provider_id,base_url,encrypted_api_key) VALUES(7,'ark',?,?)", URL, ciphertext);
  }
  @Test void existingCiphertextMigratesOnReadAndThenNeedsNoMaster() throws Exception {
    insertLegacy(legacyCiphertext());
    var expected = new Connection(7, "ark", URL, SECRET);
    assertEquals(expected, open(KEY, FALLBACK).load("ark"));
    assertEquals(SECRET, plaintext()); assertEquals("", encrypted());
    assertEquals(expected, open(null, FALLBACK).load("ark"));
    assertEquals(SECRET, store.save(7, "ark", URL, "").apiKey());
    assertEquals(8, store.load("ark").version()); assertEquals(SECRET, plaintext());
  }
  @Test void legacyDataCannotBeReadOrOverwrittenWithoutTheCorrectMaster() throws Exception {
    String ciphertext = legacyCiphertext(); insertLegacy(ciphertext);
    byte[] wrong = new byte[32]; wrong[0] = 1;
    for (String key : new String[] {"", "invalid", Base64.getEncoder().encodeToString(wrong)}) {
      var legacy = open(key, FALLBACK);
      var error = assertThrows(ArkConnectionStore.NotEditable.class, () -> legacy.load("ark"));
      assertNull(error.getCause()); assertFalse(error.getMessage().contains(SECRET));
      assertThrows(ArkConnectionStore.NotEditable.class, () -> legacy.save(7, "ark", URL, "replacement-key"));
      assertNull(plaintext()); assertEquals(ciphertext, encrypted());
    }
    jdbc.update("UPDATE media_provider_connection SET encrypted_api_key='tampered' WHERE provider_id='ark'");
    assertThrows(ArkConnectionStore.NotEditable.class, () -> open(KEY, FALLBACK).load("ark"));
    assertNull(plaintext()); assertEquals("tampered", encrypted());
  }
  @Test void migrationRefetchesAConcurrentWinnerInsteadOfOverwritingItsSavedKey() throws Exception {
    insertLegacy(legacyCiphertext());
    var racing = new JdbcTemplate(jdbc.getDataSource()) {
      boolean race = true;
      @Override public int update(String sql, Object... args) {
        if (race && sql.startsWith("UPDATE media_provider_connection SET api_key=")) {
          race = false;
          jdbc.update("UPDATE media_provider_connection SET version=8,api_key='winning-key',encrypted_api_key='' WHERE provider_id='ark'");
        }
        return super.update(sql, args);
      }
    };
    var migrated = new JdbcArkConnectionStore(racing, KEY, FALLBACK, Set.of("ark.example"));
    assertEquals(new Connection(8, "ark", URL, "winning-key"), migrated.load("ark"));
    assertEquals("winning-key", plaintext()); assertEquals("", encrypted());
  }
  @Test void validatesSafeTargetsAndKeysWithoutEchoingInput() {
    for (String url : new String[] {"https://evil.example", "https://user:pass@ark.example", "https://ark.example?key=secret", "https://ark.example#secret", "https://ark.example/api/v3/../secret", "https://ark.example/%61pi/v3", "ftp://ark.example", "http://ark.example:65536", "http://ark.example:0"}) {
      var error = assertThrows(IllegalArgumentException.class, () -> store.save(0, "ark", url, SECRET));
      assertFalse(error.getMessage().contains(url));
    }
    for (String key : new String[] {"bad key", "bad\nkey", "bad\u0000key", "x".repeat(4097)}) {
      var error = assertThrows(IllegalArgumentException.class, () -> store.save(0, "ark", URL, key));
      assertFalse(error.getMessage().contains(key));
    }
    assertThrows(IllegalArgumentException.class, () -> store.save(-1, "ark", URL, SECRET));
    assertThrows(IllegalArgumentException.class, () -> store.save(0, "bad provider", URL, SECRET));
    assertEquals(URL, store.save(0, "ark", "https://ark.example", SECRET).baseUrl());
  }
}
