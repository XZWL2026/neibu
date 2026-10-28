package com.xzwl.cloud.storage;

import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TOSV2ClientBuilder;
import com.xzwl.cloud.storage.application.file.ArchiveUrlHandler;
import com.xzwl.cloud.storage.application.file.CreateAccessUrlHandler;
import com.xzwl.cloud.storage.application.file.StoreFileHandler;
import com.xzwl.cloud.storage.infrastructure.HttpsRemoteFileSource;
import com.xzwl.cloud.storage.infrastructure.JdbcArchiveStore;
import com.xzwl.cloud.storage.infrastructure.MyBatisFileMetadataStore;
import com.xzwl.cloud.storage.infrastructure.StoredFileMapper;
import com.xzwl.cloud.storage.infrastructure.TosPrivateObjectStorage;
import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {
  @Bean
  jakarta.servlet.MultipartConfigElement multipartConfigElement(
      @Value("${storage.result-max-bytes}") long resultMaxBytes) {
    long maxFileBytes = Math.max(200L * 1024 * 1024, resultMaxBytes);
    return new jakarta.servlet.MultipartConfigElement("", maxFileBytes, Math.addExact(maxFileBytes, 1024 * 1024), 0);
  }

  @Bean java.util.function.Supplier<com.xzwl.cloud.web.ProviderConfigurationStatus> providerStatus(org.springframework.core.env.Environment env) {
    return () -> com.xzwl.cloud.web.ProviderConfigurationStatus.from("TOS",java.util.Map.of(
        "TOS_REGION",env.getProperty("storage.tos.region",""),"TOS_ENDPOINT",env.getProperty("storage.tos.endpoint",""),
        "TOS_BUCKET",env.getProperty("storage.tos.bucket",""),"TOS_ACCESS_KEY_ID",env.getProperty("storage.tos.access-key-id",""),
        "TOS_SECRET_ACCESS_KEY",env.getProperty("storage.tos.secret-access-key","")),true);
  }
  @Bean
  com.xzwl.cloud.storage.interfaces.http.StorageMediaFilter mediaServiceIdentity(
      @org.springframework.beans.factory.annotation.Value("${storage.media-token:}") String token) {
    return new com.xzwl.cloud.storage.interfaces.http.StorageMediaFilter(token);
  }

  @Bean
  MyBatisFileMetadataStore fileMetadataStore(StoredFileMapper files) {
    return new MyBatisFileMetadataStore(files);
  }

  @org.springframework.context.annotation.Lazy
  @Bean(destroyMethod = "close")
  TOSV2 tosClient(
      @Value("${storage.tos.region}") String region,
      @Value("${storage.tos.endpoint}") String endpoint,
      @Value("${storage.tos.access-key-id}") String accessKeyId,
      @Value("${storage.tos.secret-access-key}") String secretAccessKey) {
    if(java.util.stream.Stream.of(region,endpoint,accessKeyId,secretAccessKey).anyMatch(String::isBlank))
      throw new com.xzwl.cloud.storage.application.file.FileStorageException("TOS configuration unavailable");
    return new TOSV2ClientBuilder().build(region, endpoint, accessKeyId, secretAccessKey);
  }

  @Bean
  TosPrivateObjectStorage privateObjectStorage(
      org.springframework.beans.factory.ObjectProvider<TOSV2> client, @Value("${storage.tos.bucket}") String bucket) {
    return new TosPrivateObjectStorage(() -> {
      try { return client.getObject(); }
      catch(RuntimeException unavailable) { throw new com.xzwl.cloud.storage.application.file.FileStorageException("TOS configuration unavailable"); }
    }, bucket);
  }

  @Bean
  HttpsRemoteFileSource remoteFileSource(
      @Value("${storage.archive.allowed-hosts}") String allowedHosts,
      @Value("${storage.archive.connect-timeout-millis}") int connectTimeoutMillis,
      @Value("${storage.archive.read-timeout-millis}") int readTimeoutMillis,
      @Value("${storage.archive.max-redirects}") int maxRedirects,
      @Value("${storage.result-max-bytes}") long maxBytes) {
    return new HttpsRemoteFileSource(
        commaSeparatedSet(allowedHosts), connectTimeoutMillis, readTimeoutMillis, maxRedirects, maxBytes);
  }

  @Bean
  StoreFileHandler storeFileHandler(
      TosPrivateObjectStorage objects,
      MyBatisFileMetadataStore metadata,
      Clock clock,
      @Value("${storage.result-max-bytes}") long mediaResultMaxBytes) {
    return new StoreFileHandler(
        objects, metadata, () -> UUID.randomUUID().toString(), clock, mediaResultMaxBytes);
  }

  @Bean
  ArchiveUrlHandler archiveUrlHandler(
      HttpsRemoteFileSource remoteFiles, StoreFileHandler storeFiles,
      org.springframework.jdbc.core.JdbcTemplate jdbc,
      org.springframework.transaction.PlatformTransactionManager transactions,
      MyBatisFileMetadataStore metadata) {
    var receipts = new JdbcArchiveStore(jdbc, transactions, metadata);
    return new ArchiveUrlHandler(remoteFiles, storeFiles, receipts::archive);
  }

  @Bean
  CreateAccessUrlHandler createAccessUrlHandler(
      MyBatisFileMetadataStore metadata, TosPrivateObjectStorage objects, Clock clock) {
    return new CreateAccessUrlHandler(metadata, objects, clock);
  }

  @Bean
  Clock storageClock() {
    return Clock.systemUTC();
  }

  private static Set<String> commaSeparatedSet(String value) {
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(item -> !item.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
  }
}
