package com.xzwl.cloud.storage.infrastructure;

import com.xzwl.cloud.storage.application.file.FileMetadataStore;
import com.xzwl.cloud.storage.application.file.FileStorageException;
import com.xzwl.cloud.storage.domain.file.StoredFile;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.function.Function;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Same-source exclusion; completed results live in the existing file metadata table. */
public final class JdbcArchiveStore {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final FileMetadataStore metadata;

  public JdbcArchiveStore(JdbcTemplate jdbc, PlatformTransactionManager transactions, FileMetadataStore metadata) {
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(transactions);
    this.metadata = metadata;
  }

  public StoredFile archive(String identity, Function<String, StoredFile> upload) {
    String fileId = "archive-" + digest(identity);
    try {
      // ponytail: one DB connection per active transfer; use a durable async worker if pool pressure warrants it.
      return transaction.execute(status -> jdbc.execute((ConnectionCallback<StoredFile>) connection -> {
        String lock = digest("xzwl-storage-archive:" + connection.getCatalog() + ":" + fileId);
        // Cover uncertain acquisition and statement-close failures, too. Pool return follows completion.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
          @Override public void afterCompletion(int status) { release(connection, lock); }
        });
        try (var statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
          statement.setString(1, lock);
          try (var result = statement.executeQuery()) {
            if (!result.next() || result.getInt(1) != 1 || result.wasNull())
              throw new FileStorageException("archive is already in progress");
          }
        }
        return metadata.findById(fileId).orElseGet(() -> upload.apply(fileId));
      }));
    } catch (DataAccessException | TransactionException unavailable) {
      throw new FileStorageException("archive storage unavailable", unavailable);
    }
  }

  private void release(Connection connection, String lock) {
    try (var statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
      statement.setString(1, lock);
      try (var result = statement.executeQuery()) {
        // 0/null mean this session owns no lock (e.g. GET_LOCK returned busy).
        if (!result.next())
          throw new SQLException("archive lock was not released");
      }
    } catch (SQLException failure) {
      // Eviction removes the pool entry even if driver abort fails; proxy.close() would recycle it.
      if (jdbc.getDataSource() instanceof HikariDataSource pool) {
        pool.evictConnection(DataSourceUtils.getTargetConnection(connection));
      } else {
        try { connection.abort(Runnable::run); }
        catch (SQLException abortFailure) {
          failure.addSuppressed(abortFailure);
          try { DataSourceUtils.getTargetConnection(connection).close(); }
          catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
        }
      }
      throw new FileStorageException("archive lock release failed", failure);
    }
  }

  private static String digest(String identity) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(identity.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
