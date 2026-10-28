package com.xzwl.cloud.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.xzwl.cloud.storage.application.file.ArchiveUrlHandler;
import com.xzwl.cloud.storage.application.file.CreateAccessUrlHandler;
import com.xzwl.cloud.storage.application.file.StoreFileHandler;
import com.xzwl.cloud.storage.infrastructure.HttpsRemoteFileSource;
import com.xzwl.cloud.storage.infrastructure.MyBatisFileMetadataStore;
import com.xzwl.cloud.storage.domain.file.StoredFile;
import com.xzwl.cloud.storage.domain.file.FilePurpose;
import java.time.Instant;
import com.xzwl.cloud.storage.infrastructure.TosPrivateObjectStorage;
import com.xzwl.cloud.storage.interfaces.http.StorageController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = TemplateApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:storage_boot;MODE=MySQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "STORAGE_RESULT_MAX_BYTES=104857600",
      "STORAGE_ARCHIVE_ALLOWED_HOSTS=provider.example,cdn.example",
      "TOS_REGION=test-region",
      "TOS_ENDPOINT=tos.example",
      "TOS_BUCKET=private-bucket",
      "TOS_ACCESS_KEY_ID=x",
      "TOS_SECRET_ACCESS_KEY=y",
      "spring.main.banner-mode=off"
    })
class TemplateApplicationTest {
    private static JdbcTemplate mysqlAdmin;
    private static String mysqlSchema;

    @org.springframework.test.context.DynamicPropertySource
    static void isolatedMysql(org.springframework.test.context.DynamicPropertyRegistry properties) {
        String url = System.getenv("STORAGE_TEST_MYSQL_URL");
        if (url == null || url.isBlank()) return;
        if (!url.equals("jdbc:mysql://127.0.0.1:13316/")) throw new IllegalArgumentException("local test MySQL required");
        String user = System.getenv().getOrDefault("STORAGE_TEST_MYSQL_USERNAME", "root");
        String password = System.getenv().getOrDefault("STORAGE_TEST_MYSQL_PASSWORD", "");
        mysqlAdmin = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(url, user, password));
        mysqlSchema = "archive_boot_" + java.util.UUID.randomUUID().toString().replace("-", "");
        mysqlAdmin.execute("CREATE DATABASE " + mysqlSchema);
        properties.add("spring.datasource.url", () -> url + mysqlSchema);
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> password);
    }

    @org.junit.jupiter.api.AfterAll static void dropMysqlSchema() {
        if (mysqlAdmin != null && mysqlSchema != null) mysqlAdmin.execute("DROP DATABASE " + mysqlSchema);
    }

    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired MyBatisFileMetadataStore metadata;
    @org.springframework.test.context.bean.override.mockito.MockitoBean TosPrivateObjectStorage objects;
    @org.springframework.test.context.bean.override.mockito.MockitoBean HttpsRemoteFileSource remoteFiles;
    @Autowired StoreFileHandler storeFileHandler;
    @Autowired ArchiveUrlHandler archiveUrlHandler;
    @Autowired CreateAccessUrlHandler accessUrlHandler;
    @Autowired StorageController controller;
    @Autowired JdbcTemplate jdbc;

    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "STORAGE_TEST_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:13316/")
    @Test void productionArchiveWiringReplaysExistingFileAndReleasesPooledLock() throws Exception {
        String source = "https://provider.example/bootstrap.mp4";
        byte[] mp4 = {0, 0, 0, 20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
        var remote = new com.xzwl.cloud.storage.application.file.RemoteFileSource.RemoteFile() {
            public String mimeType() { return "video/mp4"; }
            public long contentLength() { return mp4.length; }
            public java.io.InputStream body() { return new java.io.ByteArrayInputStream(mp4); }
            public void close() { }
        };
        org.mockito.Mockito.when(remoteFiles.open(java.net.URI.create(source))).thenReturn(remote);
        org.mockito.Mockito.when(objects.putPrivate(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("video/mp4"), org.mockito.ArgumentMatchers.eq((long) mp4.length),
            org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
                ((java.io.InputStream) call.getArgument(3)).transferTo(java.io.OutputStream.nullOutputStream());
                return "etag";
            });
        org.mockito.Mockito.when(objects.head(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call ->
            new com.xzwl.cloud.storage.application.file.PrivateObjectStorage.StoredObject(
                call.getArgument(0), "video/mp4", mp4.length, "etag"));
        var first = archiveUrlHandler.handle("archive-owner", source);
        assertEquals(first.fileId(), archiveUrlHandler.handle("archive-owner", source).fileId());
        org.mockito.Mockito.verify(remoteFiles).open(java.net.URI.create(source));
        assertEquals(first.fileId(), metadata.findById(first.fileId()).orElseThrow().fileId());
        String lock = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(("xzwl-storage-archive:" + mysqlSchema + ":" + first.fileId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(1, jdbc.queryForObject("SELECT IS_FREE_LOCK(?)", Integer.class, lock));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() "
            + "AND table_name = 'storage_archive_request'", Integer.class));
        jdbc.update("DELETE FROM storage_file_object WHERE file_id = ?", first.fileId());
        org.mockito.Mockito.when(objects.find(org.mockito.ArgumentMatchers.anyString()))
            .thenThrow(new com.xzwl.cloud.storage.application.file.FileStorageException("HEAD unavailable"));
        org.junit.jupiter.api.Assertions.assertThrows(com.xzwl.cloud.storage.application.file.FileStorageException.class,
            () -> archiveUrlHandler.handle("archive-owner", source));
        assertEquals(1, jdbc.queryForObject("SELECT IS_FREE_LOCK(?)", Integer.class, lock));
    }

    @Test void statusReportsMissingVariableNamesAndNeverCredentialValues() throws Exception {
        var env=new org.springframework.mock.env.MockEnvironment()
            .withProperty("storage.tos.region","test").withProperty("storage.tos.endpoint","https://tos.example")
            .withProperty("storage.tos.bucket","private").withProperty("storage.tos.access-key-id","private-access-value")
            .withProperty("storage.tos.secret-access-key","private-secret-value");
        var status=new StorageConfiguration().providerStatus(env).get();
        org.junit.jupiter.api.Assertions.assertTrue(status.available());
        String response=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(status);
        org.junit.jupiter.api.Assertions.assertFalse(response.contains("private-access-value"));
        org.junit.jupiter.api.Assertions.assertFalse(response.contains("private-secret-value"));
        env.setProperty("storage.tos.secret-access-key","");
        status=new StorageConfiguration().providerStatus(env).get();
        org.junit.jupiter.api.Assertions.assertFalse(status.configured());
        assertEquals(java.util.List.of("TOS_SECRET_ACCESS_KEY"),status.missingEnvironmentVariables());
    }
    @Test void multipartAllowsTheVideoLimitPlusRequestOverhead() {
        var configuration = new StorageConfiguration();
        var referenceLimit = configuration.multipartConfigElement(100L * 1024 * 1024);
        assertEquals(200L * 1024 * 1024, referenceLimit.getMaxFileSize());
        assertEquals(201L * 1024 * 1024, referenceLimit.getMaxRequestSize());
        var largerResultLimit = configuration.multipartConfigElement(300L * 1024 * 1024);
        assertEquals(300L * 1024 * 1024, largerResultLimit.getMaxFileSize());
        assertEquals(301L * 1024 * 1024, largerResultLimit.getMaxRequestSize());
    }

    @Test
    void bootstrapIsTheSpringBootAssemblyRoot() {
        assertNotNull(TemplateApplication.class.getAnnotation(SpringBootApplication.class));
        org.junit.jupiter.api.Assertions.assertTrue(context.containsBean("browserIamSessionFilter"));
        assertNotNull(metadata);
        assertNotNull(objects);
        assertNotNull(remoteFiles);
        assertNotNull(storeFileHandler);
        assertNotNull(archiveUrlHandler);
        assertNotNull(accessUrlHandler);
        assertNotNull(controller);
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = 'storage_file_object' AND TABLE_SCHEMA = ?",
            Integer.class, mysqlSchema == null ? "PUBLIC" : mysqlSchema));
        var file = new StoredFile("bootstrap", "owner", "objects/bootstrap", "image/png", 128,
            "etag", FilePurpose.REFERENCE_IMAGE, Instant.parse("2026-09-07T08:00:00.123456Z"));
        metadata.save(file);
        assertEquals(file, metadata.findById("bootstrap").orElseThrow());
    }
}
