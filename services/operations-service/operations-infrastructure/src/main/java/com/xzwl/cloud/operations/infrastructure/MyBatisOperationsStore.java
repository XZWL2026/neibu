package com.xzwl.cloud.operations.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xzwl.cloud.operations.application.readmodel.OperationsStore;
import com.xzwl.cloud.operations.infrastructure.persistence.OperationsRecordEntity;
import com.xzwl.cloud.operations.infrastructure.persistence.OperationsRecordMapper;
import com.xzwl.cloud.operations.infrastructure.persistence.ProjectionEventEntity;
import com.xzwl.cloud.operations.infrastructure.persistence.ProjectionEventMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** MyBatis-Plus read-model store with event idempotency and monotonic source versions. */
public final class MyBatisOperationsStore implements OperationsStore {
  private static final DateTimeFormatter INSTANT_TEXT =
      new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
  private final OperationsRecordMapper records;
  private final ProjectionEventMapper events;
  private final TransactionTemplate transaction;

  public MyBatisOperationsStore(OperationsRecordMapper records, ProjectionEventMapper events,
      PlatformTransactionManager transactionManager) {
    this.records = Objects.requireNonNull(records, "records");
    this.events = Objects.requireNonNull(events, "events");
    transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    // Missing-row FOR UPDATE must not acquire competing InnoDB gap locks on first projection.
    transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  @Override
  public OperationalRecord project(Projection raw) {
    Projection projection = normalized(raw);
    String semantics = semantics(projection);
    return idempotentTransaction(() -> {
      ProjectionEventEntity replay = events.selectById(projection.eventId());
      if (replay != null) {
        if (!replay.semantics.equals(semantics)) {
          throw new IllegalStateException("projection event was replayed with different semantics");
        }
        return get(projection.type(), projection.recordId());
      }

      OperationsRecordEntity current = records.lock(projection.type().name(), projection.recordId());
      Instant projectedAt = Instant.now();
      if (current == null) {
        records.insert(entity(projection, projectedAt));
      } else {
        OperationalRecord existing = map(current);
        if (projection.sourceVersion() < existing.sourceVersion()) {
          throw new IllegalStateException("projection source version is stale");
        }
        if (projection.sourceVersion() == existing.sourceVersion()) {
          if (!same(existing, projection)) {
            throw new IllegalStateException("source version was replayed with different semantics");
          }
          projectedAt = existing.projectedAt();
        } else {
          records.replaceProjection(entity(projection, projectedAt));
        }
      }
      ProjectionEventEntity event = new ProjectionEventEntity();
      event.eventId = projection.eventId();
      event.recordType = projection.type().name();
      event.recordId = projection.recordId();
      event.semantics = semantics;
      event.projectedAt = timestamp(projectedAt);
      events.insertReceipt(event);
      return get(projection.type(), projection.recordId());
    });
  }

  private <T> T idempotentTransaction(Supplier<T> supplier) {
    try {
      return inTransaction(supplier);
    } catch (DuplicateKeyException first) {
      try {
        return inTransaction(supplier);
      } catch (DuplicateKeyException repeated) {
        throw new IllegalStateException("unique business key conflict", first);
      }
    }
  }

  private <T> T inTransaction(Supplier<T> supplier) {
    return Objects.requireNonNull(transaction.execute(ignored -> supplier.get()));
  }

  @Override
  public OperationalRecord get(RecordType type, String recordId) {
    OperationsRecordEntity record = records.selectOne(new QueryWrapper<OperationsRecordEntity>()
        .eq("record_type", type.name()).eq("record_id", recordId));
    if (record == null) throw new IllegalArgumentException("operational record not found");
    return map(record);
  }

  @Override
  public Page search(SearchFilter filter) {
    QueryWrapper<OperationsRecordEntity> query = new QueryWrapper<OperationsRecordEntity>()
        .eq(filter.type() != null, "record_type", filter.type() == null ? null : filter.type().name())
        .eq(blankToNull(filter.userId()) != null, "user_id", filter.userId())
        .eq(blankToNull(filter.status()) != null, "business_status", filter.status())
        .ge(filter.from() != null, "occurred_at", filter.from() == null ? null : timestamp(filter.from()))
        .le(filter.to() != null, "occurred_at", filter.to() == null ? null : timestamp(filter.to()))
        .ge(filter.minimumAmount() != null, "amount4", filter.minimumAmount())
        .le(filter.maximumAmount() != null, "amount4", filter.maximumAmount())
        .orderByDesc("occurred_at").orderByAsc("record_id");
    var page = records.selectPage(
        new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(filter.page() + 1L, filter.size()),
        query);
    return new Page(page.getRecords().stream().map(MyBatisOperationsStore::map).toList(),
        filter.page(), filter.size(), page.getTotal());
  }

  private static OperationalRecord map(OperationsRecordEntity row) {
    return new OperationalRecord(
        RecordType.valueOf(row.recordType), row.recordId, row.userId, row.businessStatus, row.amount4,
        Instant.parse(row.occurredAt), row.summary, row.detailsJson, row.sourceVersion,
        Instant.parse(row.projectedAt));
  }

  private static OperationsRecordEntity entity(Projection projection, Instant projectedAt) {
    OperationsRecordEntity row = new OperationsRecordEntity();
    row.recordType = projection.type().name();
    row.recordId = projection.recordId();
    row.userId = projection.userId();
    row.businessStatus = projection.status();
    row.amount4 = projection.amount();
    row.occurredAt = timestamp(projection.occurredAt());
    row.summary = projection.summary();
    row.detailsJson = projection.detailsJson();
    row.sourceVersion = projection.sourceVersion();
    row.projectedAt = timestamp(projectedAt);
    return row;
  }

  private static Projection normalized(Projection source) {
    return new Projection(source.eventId(), source.type(), source.recordId(), source.userId(),
        source.status(), money(source.amount()), source.occurredAt(), source.summary(),
        source.detailsJson(), source.sourceVersion());
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(4, RoundingMode.DOWN);
  }

  private static String timestamp(Instant value) { return INSTANT_TEXT.format(value); }

  private static boolean same(OperationalRecord current, Projection next) {
    return current.type() == next.type() && current.recordId().equals(next.recordId())
        && current.userId().equals(next.userId()) && current.status().equals(next.status())
        && Objects.equals(current.amount(), next.amount()) && current.occurredAt().equals(next.occurredAt())
        && current.summary().equals(next.summary()) && current.detailsJson().equals(next.detailsJson());
  }

  private static String semantics(Projection value) {
    return fields(value.type(), value.recordId(), value.userId(), value.status(), value.amount(),
        value.occurredAt(), value.summary(), value.detailsJson(), value.sourceVersion());
  }

  private static String fields(Object... values) {
    StringBuilder result = new StringBuilder();
    for (Object value : values) {
      String text = String.valueOf(value);
      result.append(text.length()).append(':').append(text);
    }
    return result.toString();
  }

  private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
}
