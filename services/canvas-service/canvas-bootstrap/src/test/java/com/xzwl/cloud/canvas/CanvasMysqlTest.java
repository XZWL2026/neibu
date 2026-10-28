package com.xzwl.cloud.canvas;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real HTTP and disposable MySQL schema; the external IAM endpoint is an HTTP contract fixture. */
@EnabledIfEnvironmentVariable(named="CANVAS_TEST_MYSQL_URL", matches=".+")
class CanvasMysqlTest {
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  static final String PATH = "/api/v1/canvases";
  HttpServer iam;
  ServletWebServerApplicationContext app;
  JdbcTemplate root, db;
  String schema, url, base, dbUsername, dbPassword;
  HttpClient client;
  @BeforeEach void start() throws Exception {
    String rootUrl = System.getenv("CANVAS_TEST_MYSQL_URL");
    assertTrue(rootUrl.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"));
    dbUsername = System.getenv().getOrDefault("CANVAS_TEST_MYSQL_USERNAME", "root");
    dbPassword = System.getenv().getOrDefault("CANVAS_TEST_MYSQL_PASSWORD", "");
    root = new JdbcTemplate(new DriverManagerDataSource(rootUrl, dbUsername, dbPassword));
    schema = "canvas_test_" + UUID.randomUUID().toString().replace("-", "");
    root.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
    url = rootUrl + schema + "?connectionTimeZone=UTC";
    db = new JdbcTemplate(new DriverManagerDataSource(url, dbUsername, dbPassword));
    iam = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    iam.createContext("/internal/v1/iam/session", exchange -> {
      boolean write = exchange.getRequestURI().getPath().endsWith("/verify");
      String cookie = exchange.getRequestHeaders().getFirst("Cookie");
      String owner = "XZWL_SESSION=alice".equals(cookie) ? "alice" : "XZWL_SESSION=bob".equals(cookie) ? "bob" : null;
      int status = owner == null ? 401 : write && !"valid-csrf".equals(exchange.getRequestHeaders().getFirst("X-CSRF-TOKEN")) ? 403 : 200;
      if (write && !exchange.getRequestMethod().equals("POST")) status = 405;
      byte[] body = JSON.writeValueAsBytes(status == 200 ? Map.of("authenticated", true, "roles", List.of("user"), "user", Map.of("id", owner)) : Map.of("code", "DENIED"));
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body); exchange.close();
    });
    iam.start(); client = HttpClient.newHttpClient(); restart();
  }
  void restart() throws Exception {
    if (app != null) app.close();
    // Reflective lookup deliberately makes missing production bootstrap an executable RED assertion.
    Class<?> entry;
    try { entry = Class.forName("com.xzwl.cloud.canvas.CanvasApplication"); }
    catch (ClassNotFoundException e) { throw new AssertionError("Canvas production bootstrap is missing", e); }
    app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(entry).run(
        "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off", "--logging.level.root=ERROR",
        "--spring.datasource.url=" + url, "--spring.datasource.username=" + dbUsername, "--spring.datasource.password=" + dbPassword,
        "--xzwl.iam-session.base-url=http://127.0.0.1:" + iam.getAddress().getPort());
    base = "http://127.0.0.1:" + app.getWebServer().getPort();
  }
  @AfterEach void close() {
    if (client != null) client.close();
    if (app != null) app.close();
    if (iam != null) iam.stop(0);
    if (root != null && schema != null) root.execute("DROP DATABASE " + schema);
  }
  HttpResponse<String> request(String method, String path, Object body, String owner, String csrf) throws Exception {
    var req = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
        .header("X-User-Id", "alice").header("X-Owner-Id", "alice");
    if (owner != null) req.header("Cookie", "XZWL_SESSION=" + owner);
    if (csrf != null) req.header("X-CSRF-TOKEN", csrf);
    return client.send(req.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
  }
  JsonNode ok(String method, String path, Object body, int expected) throws Exception {
    var response = request(method, path, body, "alice", "valid-csrf");
    assertEquals(expected, response.statusCode(), response.body()); return JSON.readTree(response.body());
  }
  String create() throws Exception { return ok("POST", PATH, Map.of("name", "画布"), 201).path("id").asText(); }
  String create(String owner, String name) throws Exception {
    var response = request("POST", PATH, Map.of("name", name), owner, "valid-csrf");
    assertEquals(201, response.statusCode(), response.body());
    return JSON.readTree(response.body()).path("id").asText();
  }
  Map<String,Object> content(String ref) {
    return Map.of("nodes", List.of(Map.of("id", "image-1", "kind", "image", "properties", Map.of("publicId", ref))),
        "edges", List.of(), "viewport", Map.of("x", 1.5, "y", -2.0, "zoom", 0.75));
  }
  @Test void renameSavesAtomicallyWithContentAndPreservesNameForLegacyRequests() throws Exception {
    String id = create();
    var body = Map.of("expectedVersion", 1, "name", "我的分镜", "content", content("file-1"));
    var renamed = ok("PUT", PATH + "/" + id, body, 200);
    assertEquals("我的分镜", renamed.path("name").asText());
    assertEquals(2, renamed.path("version").asLong());
    restart();
    assertEquals(renamed, ok("GET", PATH + "/" + id, null, 200));
    assertEquals("我的分镜", ok("GET", PATH, null, 200).path("items").get(0).path("name").asText());
    assertEquals(409, request("PUT", PATH + "/" + id, body, "alice", "valid-csrf").statusCode());
    assertEquals(404, request("PUT", PATH + "/" + id, Map.of("expectedVersion", 2, "name", "other", "content", content("file-1")), "bob", "valid-csrf").statusCode());
    for (String invalid : List.of(" ", "x".repeat(201))) {
      assertEquals(400, request("PUT", PATH + "/" + id, Map.of("expectedVersion", 2, "name", invalid, "content", content("file-1")), "alice", "valid-csrf").statusCode());
    }
    var legacy = ok("PUT", PATH + "/" + id, Map.of("expectedVersion", 2, "content", content("file-2")), 200);
    assertEquals("我的分镜", legacy.path("name").asText());
    assertEquals(3, legacy.path("version").asLong());
  }

  @Test void lifecyclePersistsVersionsReferencesAndOwnerAcrossRestart() throws Exception {
    String id = create();
    assertFalse(JSON.readTree(db.queryForObject("SELECT content_json FROM canvas_document WHERE id=?", String.class, id)).has("empty"), "Derived isEmpty flag must not enter persisted JSON");
    var saved = ok("PUT", PATH + "/" + id, Map.of("expectedVersion", 1, "content", content("file-1")), 200);
    assertEquals(2, saved.path("version").asLong()); assertEquals("alice", saved.path("ownerId").asText());
    restart();
    assertEquals(saved, ok("GET", PATH + "/" + id, null, 200));
    var media = ok("POST", PATH + "/" + id + "/media-references", Map.of("expectedVersion", 2, "nodeId", "video-1", "kind", "VIDEO", "publicId", "https://cdn.example.test/result.mp4"), 200);
    assertEquals(2, media.path("content").path("nodes").size());
    var copy = ok("POST", PATH + "/" + id + "/copies", Map.of("name", "副本"), 201);
    assertNotEquals(id, copy.path("id").asText()); assertEquals(1, copy.path("version").asLong());
    assertEquals(media.path("content"), copy.path("content"));
    assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM canvas_version WHERE canvas_id=?", Integer.class, id));
    assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM canvas_media_reference WHERE canvas_id=? AND version=3", Integer.class, id));
    assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM canvas_media_reference WHERE canvas_id=?", Integer.class, copy.path("id").asText()));
    var deleted = ok("DELETE", PATH + "/" + id + "?expectedVersion=3", null, 200);
    assertTrue(deleted.path("deleted").asBoolean()); assertEquals(4, deleted.path("version").asLong());
    assertEquals(404, request("GET", PATH + "/" + id, null, "alice", null).statusCode());
    assertEquals(404, request("POST", PATH + "/" + id + "/copies", Map.of("name", "copy"), "alice", "valid-csrf").statusCode());
    assertEquals(4, db.queryForObject("SELECT COUNT(*) FROM canvas_version WHERE canvas_id=?", Integer.class, id));
    ok("GET", PATH + "/" + copy.path("id").asText(), null, 200);
  }
  @Test void authenticationCsrfAndEveryOperationEnforceOwner() throws Exception {
    assertEquals(401, request("POST", PATH, Map.of("name", "x"), null, null).statusCode());
    assertEquals(401, request("GET", PATH + "/missing", null, "expired", null).statusCode());
    assertEquals(403, request("POST", PATH, Map.of("name", "x"), "alice", null).statusCode());
    assertEquals(403, request("POST", PATH, Map.of("name", "x"), "alice", "wrong").statusCode());
    String id = create();
    assertEquals(404, request("GET", PATH + "/" + id, null, "bob", null).statusCode());
    assertEquals(404, request("PUT", PATH + "/" + id, Map.of("expectedVersion", 1, "content", content("file-1")), "bob", "valid-csrf").statusCode());
    assertEquals(404, request("DELETE", PATH + "/" + id + "?expectedVersion=1", null, "bob", "valid-csrf").statusCode());
    assertEquals(404, request("POST", PATH + "/" + id + "/copies", Map.of("name", "steal"), "bob", "valid-csrf").statusCode());
    assertEquals(404, request("POST", PATH + "/" + id + "/media-references", Map.of("expectedVersion", 1, "nodeId", "image-1", "kind", "IMAGE", "publicId", "file-1"), "bob", "valid-csrf").statusCode());
    assertEquals(1, ok("GET", PATH + "/" + id, null, 200).path("version").asLong());
    iam.stop(0);
    assertEquals(503, request("GET", PATH + "/" + id, null, "alice", null).statusCode());
  }
  @Test void listRequiresAuthenticationAndUsesOnlyTheAuthenticatedOwner() throws Exception {
    assertEquals(401, request("GET", PATH, null, null, null).statusCode());
    String alice = create("alice", "Alice");
    String bob = create("bob", "Bob");

    var response = request("GET", PATH, null, "alice", null);
    assertEquals(200, response.statusCode(), response.body());
    var items = JSON.readTree(response.body()).path("items");
    assertEquals(List.of(alice), List.of(items.get(0).path("id").asText()));
    assertFalse(response.body().contains(bob));
  }
  @Test void listFiltersDeletedAndOrdersStablyBeforePaginating() throws Exception {
    String deleted = create("alice", "Deleted");
    String oldest = create("alice", "Oldest");
    String sameTimeLowerId = create("alice", "Lower");
    String sameTimeHigherId = create("alice", "Higher");
    db.update("UPDATE canvas_document SET updated_at='2026-01-01 00:00:00.000000' WHERE id=?", oldest);
    db.update("UPDATE canvas_document SET updated_at='2026-01-02 00:00:00.000000' WHERE id IN (?,?)", sameTimeLowerId, sameTimeHigherId);
    ok("DELETE", PATH + "/" + deleted + "?expectedVersion=1", null, 200);

    var first = ok("GET", PATH + "?offset=0&limit=2", null, 200);
    var sameTime = new ArrayList<>(List.of(sameTimeLowerId, sameTimeHigherId));
    sameTime.sort(Comparator.reverseOrder());
    assertEquals(sameTime, ids(first.path("items")));
    assertTrue(first.path("hasMore").asBoolean());
    var second = ok("GET", PATH + "?offset=2&limit=2", null, 200);
    assertEquals(List.of(oldest), ids(second.path("items")));
    assertFalse(second.path("hasMore").asBoolean());
  }
  @Test void listValidatesPaginationIncludingOverflowingValues() throws Exception {
    assertEquals(200, request("GET", PATH, null, "alice", null).statusCode());
    for (String query : List.of("?offset=-1", "?limit=0", "?limit=25", "?offset=2147483648", "?limit=2147483648")) {
      assertEquals(400, request("GET", PATH + query, null, "alice", null).statusCode(), query);
    }
  }
  @Test void listReturnsAtMostEightSanitizedPreviewNodes() throws Exception {
    String id = create();
    var nodes = new ArrayList<Map<String,Object>>();
    for (int i = 0; i < 9; i++) {
      var properties = new LinkedHashMap<String,String>();
      properties.put("x", Integer.toString(i)); properties.put("y", Integer.toString(-i));
      properties.put("text", "文".repeat(161)); properties.put("alt", "alt ".repeat(41));
      properties.put("taskId", "secret-task"); properties.put("model", "secret-model");
      if (i == 0) properties.put("fileId", "legacy-file");
      else if (i == 1) properties.put("url", "https://cdn.example.test/legacy.png");
      else if (i == 2) properties.put("src", "legacy-src");
      else properties.put("publicId", "public-" + i);
      nodes.add(Map.of("id", "node-" + i, "kind", "image", "properties", properties));
    }
    var content = Map.of("nodes", nodes, "edges", List.of(), "viewport", Map.of("x", 0, "y", 0, "zoom", 1));
    ok("PUT", PATH + "/" + id, Map.of("expectedVersion", 1, "content", content), 200);

    var preview = ok("GET", PATH, null, 200).at("/items/0/previewNodes");
    assertEquals(8, preview.size());
    assertEquals("legacy-file", preview.get(0).at("/properties/publicId").asText());
    assertEquals("https://cdn.example.test/legacy.png", preview.get(1).at("/properties/publicId").asText());
    assertEquals("legacy-src", preview.get(2).at("/properties/publicId").asText());
    assertEquals(160, preview.get(0).at("/properties/text").asText().length());
    assertEquals(160, preview.get(0).at("/properties/alt").asText().length());
    assertEquals(Set.of("x", "y", "text", "alt", "publicId"), propertyNames(preview.get(0).path("properties")));
    assertFalse(preview.toString().contains("secret-task"));
    assertFalse(preview.toString().contains("secret-model"));
  }
  private static List<String> ids(JsonNode items) {
    var ids = new ArrayList<String>();
    items.forEach(item -> ids.add(item.path("id").asText()));
    return ids;
  }
  private static Set<String> propertyNames(JsonNode properties) {
    var names = new HashSet<String>();
    properties.fieldNames().forEachRemaining(names::add);
    return names;
  }
  @Test void competingWritersCannotLoseUpdatesAndFailedHistoryRollsBack() throws Exception {
    String id = create();
    try (var workers = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      Callable<Integer> save = () -> { gate.await(); return request("PUT", PATH + "/" + id, Map.of("expectedVersion", 1, "content", content("file-1")), "alice", "valid-csrf").statusCode(); };
      var a = workers.submit(save); var b = workers.submit(save); gate.countDown();
      assertEquals(Set.of(200,409), new HashSet<>(List.of(a.get(),b.get())));
    }
    assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM canvas_version WHERE canvas_id=?", Integer.class, id));
    // Failure after current-row CAS must roll the entire local transaction back.
    db.execute("CREATE TRIGGER reject_canvas_history BEFORE INSERT ON canvas_version FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test history failure'");
    try { assertEquals(500, request("PUT", PATH + "/" + id, Map.of("expectedVersion", 2, "content", content("file-2")), "alice", "valid-csrf").statusCode()); }
    finally { db.execute("DROP TRIGGER reject_canvas_history"); }
    var unchanged = ok("GET", PATH + "/" + id, null, 200);
    assertEquals(2, unchanged.path("version").asLong());
    assertEquals("file-1", unchanged.at("/content/nodes/0/properties/publicId").asText());
  }
  @Test
  @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="canvas.test.gateway-jar", matches=".+")
  void gatewayToCanvasPersistsAllSixOperations() throws Exception {
    int port;
    try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
    var log = java.nio.file.Files.createTempFile("canvas-gateway-", ".log");
    var gateway = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-jar", System.getProperty("canvas.test.gateway-jar"), "--server.port=" + port,
        "--server.address=127.0.0.1", "--logging.level.root=ERROR", "--spring.cloud.nacos.discovery.enabled=false",
        "--spring.cloud.nacos.config.enabled=false", "--gateway.upstreams.canvas=" + base)
        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    String direct = base;
    try {
      base = "http://127.0.0.1:" + port;
      boolean ready = false;
      for (int i = 0; i < 150 && gateway.isAlive(); i++) {
        try {
          var response = client.send(HttpRequest.newBuilder(URI.create(base + "/actuator/health"))
              .timeout(java.time.Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.discarding());
          if (response.statusCode() == 200) { ready = true; break; }
        } catch (java.io.IOException starting) { /* Poll only this child process's readiness. */ }
        Thread.sleep(100);
      }
      assertTrue(ready, () -> "Gateway not ready, log: " + log);
      String id = create();
      ok("GET", PATH + "/" + id, null, 200);
      ok("PUT", PATH + "/" + id, Map.of("expectedVersion", 1, "content", content("file-1")), 200);
      ok("POST", PATH + "/" + id + "/media-references", Map.of("expectedVersion", 2, "nodeId", "video-1", "kind", "VIDEO", "publicId", "file-2"), 200);
      String copy = ok("POST", PATH + "/" + id + "/copies", Map.of("name", "副本"), 201).path("id").asText();
      assertEquals(404, request("GET", PATH + "/" + copy, null, "bob", null).statusCode());
      assertEquals(403, request("DELETE", PATH + "/" + id + "?expectedVersion=3", null, "alice", "invalid").statusCode());
      ok("DELETE", PATH + "/" + id + "?expectedVersion=3", null, 200);
      assertEquals(404, request("GET", PATH + "/" + id, null, "alice", null).statusCode());
      assertEquals(4, db.queryForObject("SELECT version FROM canvas_document WHERE id=?", Integer.class, id));
      assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM canvas_media_reference WHERE canvas_id=?", Integer.class, copy));
    } finally {
      base = direct; gateway.destroy();
      if (!gateway.waitFor(10, TimeUnit.SECONDS)) { gateway.destroyForcibly(); gateway.waitFor(); }
    }
  }
  @Test void rejectsNumericEnumAndFractionalVersionInsteadOfCoercingThem() throws Exception {
    String id = create();
    assertEquals(400, request("POST", PATH + "/" + id + "/media-references", Map.of("expectedVersion", 1, "nodeId", "node-1", "kind", 0, "publicId", "file-1"), "alice", "valid-csrf").statusCode());
    assertEquals(400, request("PUT", PATH + "/" + id, Map.of("expectedVersion", 1.9, "content", content("file-1")), "alice", "valid-csrf").statusCode());
    assertEquals(1, ok("GET", PATH + "/" + id, null, 200).path("version").asLong());
  }
  @Test void rejectsInlineMediaMalformedSnapshotsAndDuplicateNodesBeforeWrites() throws Exception {
    String id = create();
    for (String invalid : List.of("data:image/png;base64,AAAA", "DATA:image/png;base64,AAAA", "http://cdn.example.test/a", "blob:local", "https://user:secret@cdn.example.test/a", "SGVsbG8=")) {
      assertEquals(400, request("PUT", PATH + "/" + id, Map.of("expectedVersion", 1, "content", content(invalid)), "alice", "valid-csrf").statusCode(), invalid);
      assertEquals(400, request("POST", PATH + "/" + id + "/media-references", Map.of("expectedVersion", 1, "nodeId", "image-1", "kind", "IMAGE", "publicId", invalid), "alice", "valid-csrf").statusCode(), invalid);
    }
    assertEquals(400, request("POST", PATH, Map.of("name", "x", "ownerId", "bob"), "alice", "valid-csrf").statusCode());
    assertEquals(400, request("PUT", PATH + "/" + id, Map.of("expectedVersion", 1), "alice", "valid-csrf").statusCode());
    ok("PUT", PATH + "/" + id, Map.of("expectedVersion", 1, "content", content("file-1")), 200);
    assertEquals(400, request("POST", PATH + "/" + id + "/media-references", Map.of("expectedVersion", 2, "nodeId", "image-1", "kind", "IMAGE", "publicId", "file-2"), "alice", "valid-csrf").statusCode());
    assertEquals(2, ok("GET", PATH + "/" + id, null, 200).path("version").asLong());
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND data_type IN ('blob','tinyblob','mediumblob','longblob','binary','varbinary')", Integer.class, schema));
  }
}
