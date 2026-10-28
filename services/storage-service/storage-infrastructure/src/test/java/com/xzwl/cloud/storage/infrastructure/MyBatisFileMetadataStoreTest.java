package com.xzwl.cloud.storage.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.xzwl.cloud.storage.domain.file.FilePurpose;
import com.xzwl.cloud.storage.domain.file.StoredFile;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class MyBatisFileMetadataStoreTest {
  @TempDir Path directory;
  private DriverManagerDataSource dataSource;
  private JdbcTemplate mysqlAdmin;
  private String schema;

  @BeforeEach
  void migrate() {
    String mysqlUrl = System.getenv("STORAGE_TEST_MYSQL_URL");
    if (mysqlUrl == null || mysqlUrl.isBlank()) {
      dataSource = new DriverManagerDataSource(
          "jdbc:h2:file:" + directory.resolve("storage") + ";MODE=MySQL", "sa", "");
    } else {
      if (!mysqlUrl.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/")) {
        throw new IllegalArgumentException("STORAGE_TEST_MYSQL_URL must address local MySQL without a schema");
      }
      String username = System.getenv().getOrDefault("STORAGE_TEST_MYSQL_USERNAME", "root");
      String password = System.getenv().getOrDefault("STORAGE_TEST_MYSQL_PASSWORD", "");
      mysqlAdmin = new JdbcTemplate(new DriverManagerDataSource(mysqlUrl, username, password));
      schema = "storage_mp_" + UUID.randomUUID().toString().replace("-", "");
      mysqlAdmin.execute("CREATE DATABASE " + schema);
      dataSource = new DriverManagerDataSource(mysqlUrl + schema, username, password);
    }
    new ResourceDatabasePopulator(
        new ClassPathResource("db/migration/V1__storage_file_object.sql"),
        new ClassPathResource("db/migration/V2__reference_media_metadata.sql"))
        .execute(dataSource);
  }

  @AfterEach
  void dropTestSchema() {
    if (mysqlAdmin != null && schema != null) mysqlAdmin.execute("DROP DATABASE " + schema);
  }

  private MyBatisFileMetadataStore repository() throws Exception {
    var configuration = new MybatisConfiguration();
    configuration.addMapper(StoredFileMapper.class);
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(new DriverManagerDataSource(
        dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword()));
    factory.setConfiguration(configuration);
    return new MyBatisFileMetadataStore(
        new SqlSessionTemplate(factory.getObject()).getMapper(StoredFileMapper.class));
  }

  @Test void backfillsOnlyMediaMetadataWithoutChangingFileIdentityOrOwner() throws Exception {
    var file = new StoredFile("generated-1", "owner-1", "objects/generated-1", "video/mp4", 1000, "etag",
        FilePurpose.MEDIA_RESULT, Instant.parse("2026-09-10T00:00:00Z"));
    var repository = repository();
    repository.save(file);
    var enriched = new StoredFile(file.fileId(), file.ownerId(), file.objectKey(), file.mimeType(), file.sizeBytes(),
        file.etag(), file.purpose(), file.createdAt(), 3.0, 1280, 720, 24.0);
    repository.updateMediaMetadata(enriched);
    assertEquals(enriched, repository().findById(file.fileId()).orElseThrow());
    var wrongOwner = new StoredFile(file.fileId(), "other", file.objectKey(), file.mimeType(), file.sizeBytes(),
        file.etag(), file.purpose(), file.createdAt(), 9.0, 1280, 720, 24.0);
    assertThrows(com.xzwl.cloud.storage.application.file.FileStorageException.class, () -> repository.updateMediaMetadata(wrongOwner));
    assertEquals(enriched, repository().findById(file.fileId()).orElseThrow());
  }

  @Test void persistsNullableReferenceMediaMetadataAcrossRepositoryInstances() throws Exception {
    var video = new StoredFile("video-1", "owner-1", "objects/video-1", "video/mp4", 12345, "etag",
        FilePurpose.REFERENCE_VIDEO, Instant.parse("2026-09-10T00:00:00Z"), 3.25, 1280, 720, 29.97);
    repository().save(video);
    assertEquals(video, repository().findById("video-1").orElseThrow());
    var audio = new StoredFile("audio-1", "owner-1", "objects/audio-1", "audio/mpeg", 6789, "etag",
        FilePurpose.REFERENCE_AUDIO, video.createdAt(), 2.75, null, null, null);
    repository().save(audio);
    assertEquals(audio, repository().findById("audio-1").orElseThrow());
  }

  @Test
  void persistsOnlyPrivateObjectMetadataAcrossRepositoryInstances() throws Exception {
    StoredFile file = new StoredFile(
        "file-1", "owner-1", "objects/file-1", "image/png", 128, "etag-1",
        FilePurpose.REFERENCE_IMAGE, Instant.parse("2026-09-07T08:00:00.123456Z"));

    var first = repository();
    assertTrue(first.findById("missing").isEmpty());
    first.save(file);

    var reopened = repository();
    assertEquals(file, reopened.findById(file.fileId()).orElseThrow());
    assertThrows(DuplicateKeyException.class, () -> reopened.save(file));
    assertThrows(DuplicateKeyException.class, () -> reopened.save(new StoredFile(
        "file-2", file.ownerId(), file.objectKey(), file.mimeType(), file.sizeBytes(), file.etag(),
        file.purpose(), file.createdAt())));
    assertTrue(reopened.findById("file-2").isEmpty());
    assertEquals(file, reopened.findById(file.fileId()).orElseThrow());
  }
}
