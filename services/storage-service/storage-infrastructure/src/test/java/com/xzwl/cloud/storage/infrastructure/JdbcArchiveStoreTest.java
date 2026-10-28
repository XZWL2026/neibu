package com.xzwl.cloud.storage.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.sun.net.httpserver.HttpServer;
import com.xzwl.cloud.storage.application.file.*;
import com.xzwl.cloud.storage.domain.file.StoredFile;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "STORAGE_TEST_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:13316/")
class JdbcArchiveStoreTest {
  private static final String SOURCE = "https://provider.example/result.mp4?token=private";
  private static final byte[] MP4 = {0, 0, 0, 20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
  private DriverManagerDataSource dataSource;
  private JdbcTemplate jdbc;
  private JdbcTemplate mysqlAdmin;
  private String schema;
  private final AtomicInteger downloads = new AtomicInteger();
  private final SlowObjects objects = new SlowObjects();
  private final AtomicBoolean failMetadata = new AtomicBoolean();

  @BeforeEach void migrate() {
    String mysqlUrl = System.getenv("STORAGE_TEST_MYSQL_URL");
    if (!"jdbc:mysql://127.0.0.1:13316/".equals(mysqlUrl))
      throw new IllegalArgumentException("archive tests require the dedicated local test MySQL");
    String user = System.getenv().getOrDefault("STORAGE_TEST_MYSQL_USERNAME", "root");
    String password = System.getenv().getOrDefault("STORAGE_TEST_MYSQL_PASSWORD", "");
    mysqlAdmin = new JdbcTemplate(new DriverManagerDataSource(mysqlUrl, user, password));
    schema = "archive_retry_" + UUID.randomUUID().toString().replace("-", "");
    mysqlAdmin.execute("CREATE DATABASE " + schema);
    dataSource = new DriverManagerDataSource(mysqlUrl + schema, user, password);
    jdbc = new JdbcTemplate(dataSource);
    new ResourceDatabasePopulator(
        new ClassPathResource("db/migration/V1__storage_file_object.sql"),
        new ClassPathResource("db/migration/V2__reference_media_metadata.sql"))
        .execute(dataSource);
  }

  @AfterEach void dropTestSchema() {
    if (mysqlAdmin != null && schema != null) mysqlAdmin.execute("DROP DATABASE " + schema);
  }

  @Test void releaseFailureEvictsTheSessionEvenWhenDriverAbortIsBroken() throws Exception {
    for (boolean brokenAbort : new boolean[] {false, true}) {
      var closed = new CountDownLatch(1);
      try (var pool = faultPool(false, true, brokenAbort, closed)) {
        var pooledJdbc = new JdbcTemplate(pool);
        var store = lockStore(pool, pooledJdbc);
        var session = new AtomicLong();
        store.archive("release-failure", id -> {
          session.set(pooledJdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
          return null;
        });
        assertTrue(closed.await(3, TimeUnit.SECONDS), "failed session was not physically closed");
        assertNotEquals(session.get(), pooledJdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT IS_FREE_LOCK(?)", Integer.class,
            lockName("release-failure")));
      }
    }
  }

  @Test void acquisitionStatementCloseFailureStillReleasesTheLock() throws Exception {
    try (var pool = faultPool(true, false, false, new CountDownLatch(1))) {
      var pooledJdbc = new JdbcTemplate(pool);
      var store = lockStore(pool, pooledJdbc);
      assertThrows(FileStorageException.class, () -> store.archive("close-failure", id -> {
        fail("upload must not start after acquisition cleanup failure");
        return null;
      }));
      assertEquals(1, jdbc.queryForObject("SELECT IS_FREE_LOCK(?)", Integer.class,
          lockName("close-failure")));
      assertNotNull(pooledJdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
    }
  }

  private String lockName(String identity) throws Exception {
    var digest = java.security.MessageDigest.getInstance("SHA-256");
    String id = "archive-" + HexFormat.of().formatHex(digest.digest(
        identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    return HexFormat.of().formatHex(digest.digest(("xzwl-storage-archive:" + schema + ":" + id)
        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  private JdbcArchiveStore lockStore(javax.sql.DataSource source, JdbcTemplate template) {
    return new JdbcArchiveStore(template,
        new org.springframework.jdbc.datasource.DataSourceTransactionManager(source), new FileMetadataStore() {
          public void save(StoredFile file) { throw new AssertionError("not needed"); }
          public Optional<StoredFile> findById(String id) { return Optional.empty(); }
        });
  }

  private com.zaxxer.hikari.HikariDataSource faultPool(boolean closeAcquisition, boolean release,
      boolean abort, CountDownLatch closed) {
    var faultSource = new org.springframework.jdbc.datasource.AbstractDataSource() {
      public java.sql.Connection getConnection(String user, String password) throws java.sql.SQLException {
        return getConnection();
      }
      public java.sql.Connection getConnection() throws java.sql.SQLException {
        var physical = dataSource.getConnection();
        return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] {java.sql.Connection.class}, (proxy, method, args) -> {
              if (method.getName().equals("abort") && abort) throw new java.sql.SQLException("abort failed");
              if (method.getName().equals("prepareStatement") && args[0].equals("SELECT RELEASE_LOCK(?)")
                  && release) throw new java.sql.SQLException("release failed");
              Object result = invoke(physical, method, args);
              if (method.getName().equals("close")) closed.countDown();
              if (method.getName().equals("prepareStatement") && args[0].equals("SELECT GET_LOCK(?, 0)")
                  && closeAcquisition) {
                return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {java.sql.PreparedStatement.class}, (statement, statementMethod, statementArgs) -> {
                      Object value = invoke(result, statementMethod, statementArgs);
                      if (statementMethod.getName().equals("close")) throw new java.sql.SQLException("close failed");
                      return value;
                    });
              }
              return result;
            });
      }
    };
    var pool = new com.zaxxer.hikari.HikariDataSource();
    pool.setDataSource(faultSource);
    pool.setMaximumPoolSize(1);
    pool.setMinimumIdle(0);
    pool.setInitializationFailTimeout(-1);
    return pool;
  }

  private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
    try { return method.invoke(target, args); }
    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
  }

  @Test void rotatedSignaturesShareOneFlightAndSurviveRestartButOutputsStayIsolated() throws Exception {
    String key = "task-1:RESULT:0";
    objects.pauseFirst = true;
    var first = handler();
    var retry = handler();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var pending = executor.submit(() -> first.handle("owner", SOURCE, key));
      try {
        assertTrue(objects.entered.await(3, TimeUnit.SECONDS));
        assertThrows(FileStorageException.class, () -> retry.handle("owner", SOURCE + "-new-signature", key));
        assertEquals(1, downloads.get());
        assertEquals(1, objects.puts.get());
      } finally { objects.release.countDown(); }
      var file = pending.get(3, TimeUnit.SECONDS);
      assertEquals(file, handler().handle("owner", SOURCE + "-third-signature", key));
      assertEquals(1, downloads.get());
      assertEquals(1, objects.puts.get());
      var ids = new HashSet<String>();
      ids.add(file.fileId());
      ids.add(handler().handle("other-owner", SOURCE, key).fileId());
      for (String different : List.of("task-2:RESULT:0", "task-1:NATIVE:0",
          "task-1:ENHANCEMENT:0", "task-1:RESULT:1"))
        ids.add(handler().handle("owner", SOURCE, different).fileId());
      assertEquals(6, ids.size());
      assertEquals(6, downloads.get());
      assertEquals(6, objects.puts.get());
      jdbc.update("DELETE FROM storage_file_object WHERE file_id=?", file.fileId());
      assertEquals(file, handler().handle("owner", SOURCE + "-fourth-signature", key));
      assertEquals(6, downloads.get());
      assertEquals(6, objects.puts.get());
    }
  }

  @Test void archivesWithoutCreatingReceiptTable() throws Exception {
    var archive = handler();
    var file = assertDoesNotThrow(() -> archive.handle("owner", SOURCE));
    assertEquals(file.fileId(), archive.handle("owner", SOURCE).fileId());
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
        + "WHERE table_schema = DATABASE() AND table_name = 'storage_archive_request'", Integer.class));
    assertEquals(1, downloads.get());
  }

  private ArchiveUrlHandler handler() throws Exception {
    var configuration = new MybatisConfiguration();
    configuration.addMapper(StoredFileMapper.class);
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(dataSource);
    factory.setConfiguration(configuration);
    var metadata = new MyBatisFileMetadataStore(
        new SqlSessionTemplate(factory.getObject()).getMapper(StoredFileMapper.class));
    FileMetadataStore failingMetadata = new FileMetadataStore() {
      public void save(StoredFile file) {
        metadata.save(file);
        if (failMetadata.getAndSet(false)) throw new FileStorageException("metadata receipt failure");
      }
      public Optional<StoredFile> findById(String id) { return metadata.findById(id); }
    };
    var store = new StoreFileHandler(objects, failingMetadata, () -> UUID.randomUUID().toString(),
        Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC), 1024);
    RemoteFileSource remote = source -> {
      downloads.incrementAndGet();
      return new RemoteFileSource.RemoteFile() {
        public String mimeType() { return "video/mp4"; }
        public long contentLength() { return MP4.length; }
        public InputStream body() { return new ByteArrayInputStream(MP4); }
        public void close() { }
      };
    };
    var receipts = new JdbcArchiveStore(jdbc,
        new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource), metadata);
    return new ArchiveUrlHandler(remote, store, receipts::archive);
  }

  @Test void httpTimeoutAndRetryDoNotStartAnotherDownloadOrUpload() throws Exception {
    objects.pauseFirst = true;
    var firstHandler = handler();
    var retryHandler = handler(); // Separate application instances, same database.
    var requests = new AtomicInteger();
    var retryEntered = new CountDownLatch(1);
    var firstFinished = new CountDownLatch(1);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var client = HttpClient.newHttpClient()) {
      server.setExecutor(executor);
      server.createContext("/archive", exchange -> {
        int n = requests.incrementAndGet();
        try (exchange) {
          if (n > 1) retryEntered.countDown();
          StoredFile file;
          try { file = (n == 1 ? firstHandler : retryHandler).handle("owner", SOURCE); }
          catch (FileStorageException busy) { exchange.sendResponseHeaders(502, -1); return; }
          byte[] response = file.fileId().getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
        } catch (IOException disconnected) { /* The first HTTP client has timed out. */ }
        finally { if (n == 1) firstFinished.countDown(); }
      });
      server.start();
      try {
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/archive");
        var first = client.sendAsync(HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(500))
            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(objects.entered.await(3, TimeUnit.SECONDS));
        var timeout = assertThrows(ExecutionException.class, () -> first.get(3, TimeUnit.SECONDS));
        assertInstanceOf(HttpTimeoutException.class, timeout.getCause());
        var retry = client.sendAsync(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(retryEntered.await(3, TimeUnit.SECONDS));
        assertFalse(objects.duplicate.await(300, TimeUnit.MILLISECONDS), "retry started a second TOS upload");
        assertEquals(1, downloads.get(), "retry re-downloaded the same source");
        assertEquals(502, retry.get(5, TimeUnit.SECONDS).statusCode(), "in-flight archive stays retryable");
        objects.release.countDown();
        assertTrue(firstFinished.await(3, TimeUnit.SECONDS));
        var response = client.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals(response.body(), handler().handle("owner", SOURCE).fileId());
        assertEquals(1, downloads.get());
        assertEquals(1, objects.puts.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM storage_file_object", Integer.class));
      } finally {
        objects.release.countDown();
        server.stop(0);
      }
    }
  }

  @Test void completedFileSurvivesNewHandlerAndKeepsOwnersAndUrlsSeparate() throws Exception {
    var first = handler().handle("owner", SOURCE);
    assertEquals(first, handler().handle("owner", SOURCE));
    assertNotEquals(first.fileId(), handler().handle("other-owner", SOURCE).fileId());
    assertNotEquals(first.fileId(), handler().handle("owner", SOURCE + "-different").fileId());
    assertEquals(3, downloads.get());
    assertEquals(3, objects.puts.get());
  }

  @Test void failedUploadCanBeRetriedAndThenReplayed() throws Exception {
    objects.failNext.set(true);
    assertThrows(FileStorageException.class, () -> handler().handle("owner", SOURCE));
    var file = handler().handle("owner", SOURCE);
    assertEquals(file, handler().handle("owner", SOURCE));
    assertEquals(2, objects.puts.get());
  }

  @Test void metadataFailureRollsBackAndRetryRecoversVerifiedObject() throws Exception {
    failMetadata.set(true);
    assertThrows(FileStorageException.class, () -> handler().handle("owner", SOURCE));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM storage_file_object", Integer.class));
    assertEquals(1, objects.stored.size(), "verified object is retained for recovery");
    var file = handler().handle("owner", SOURCE);
    assertEquals(file, handler().handle("owner", SOURCE));
    assertEquals(1, objects.puts.get());
    assertEquals(1, downloads.get());
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM storage_file_object", Integer.class));
  }

  @Test void differentArchivesDoNotShareOneGlobalLock() throws Exception {
    objects.pauseFirst = true;
    var first = handler();
    var other = handler();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var pending = executor.submit(() -> first.handle("owner", SOURCE));
      try {
        assertTrue(objects.entered.await(3, TimeUnit.SECONDS));
        var independent = executor.submit(() -> other.handle("owner", SOURCE + "-other"));
        assertNotNull(independent.get(3, TimeUnit.SECONDS));
        assertFalse(pending.isDone());
      } finally { objects.release.countDown(); }
      assertNotNull(pending.get(3, TimeUnit.SECONDS));
    }
  }

  @Test void busyLockNeverFallsBackToAnotherUpload() throws Exception {
    var file = handler().handle("owner", SOURCE);
    String lock = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(("xzwl-storage-archive:" + schema + ":" + file.fileId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
      statement.setString(1, lock);
      try (var result = statement.executeQuery()) { assertTrue(result.next()); assertEquals(1, result.getInt(1)); }
      assertThrows(FileStorageException.class, () -> handler().handle("owner", SOURCE));
      assertEquals(1, downloads.get());
      assertEquals(1, objects.puts.get());
    }
    assertEquals(file, handler().handle("owner", SOURCE));
    assertEquals(1, jdbc.queryForObject("SELECT IS_FREE_LOCK(?)", Integer.class, lock));
  }

  @Test void existingObjectWithoutMetadataIsRecoveredWithoutDownloadOrPut() throws Exception {
    var original = handler().handle("owner", SOURCE);
    jdbc.update("DELETE FROM storage_file_object WHERE file_id = ?", original.fileId());
    var recovered = handler().handle("owner", SOURCE);
    assertEquals(original.fileId(), recovered.fileId());
    assertEquals(original.objectKey(), recovered.objectKey());
    assertEquals(1, downloads.get());
    assertEquals(1, objects.puts.get());
  }

  private static final class SlowObjects implements PrivateObjectStorage {
    final AtomicInteger puts = new AtomicInteger();
    final AtomicBoolean failNext = new AtomicBoolean();
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), duplicate = new CountDownLatch(1);
    final Map<String, StoredObject> stored = new ConcurrentHashMap<>();
    boolean pauseFirst;

    public String putPrivate(String key, String mime, long size, InputStream body) {
      int count = puts.incrementAndGet();
      if (count > 1) duplicate.countDown();
      try {
        if (pauseFirst && count == 1) {
          entered.countDown();
          if (!release.await(10, TimeUnit.SECONDS)) throw new FileStorageException("test upload timed out");
        }
        if (failNext.getAndSet(false)) throw new FileStorageException("test upload failed");
        assertEquals(size, body.transferTo(OutputStream.nullOutputStream()));
        stored.put(key, new StoredObject(key, mime, size, "etag"));
        return "etag";
      } catch (IOException | InterruptedException failure) { throw new FileStorageException("test upload failed", failure); }
    }
    public StoredObject head(String key) { return stored.get(key); }
    public void delete(String key) { stored.remove(key); }
    public String signGet(String key, long seconds) { return "unused"; }
  }
}
