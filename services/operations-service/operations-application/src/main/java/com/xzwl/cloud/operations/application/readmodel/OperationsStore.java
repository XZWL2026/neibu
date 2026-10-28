package com.xzwl.cloud.operations.application.readmodel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Persistence boundary for the operations service's own denormalized read model. */
public interface OperationsStore {
  OperationalRecord project(Projection projection);
  OperationalRecord get(RecordType type, String recordId);
  Page search(SearchFilter filter);

  enum RecordType { USER, RECHARGE, CONSUMPTION, IMAGE, VIDEO, PROMOTION }

  record Projection(
      String eventId, RecordType type, String recordId, String userId, String status,
      BigDecimal amount, Instant occurredAt, String summary, String detailsJson,
      long sourceVersion) {}

  record OperationalRecord(
      RecordType type, String recordId, String userId, String status, BigDecimal amount,
      Instant occurredAt, String summary, String detailsJson, long sourceVersion,
      Instant projectedAt) {}

  record SearchFilter(
      RecordType type, String userId, String status, Instant from, Instant to,
      BigDecimal minimumAmount, BigDecimal maximumAmount, int page, int size) {}

  record Page(List<OperationalRecord> items, int page, int size, long total) {
    public Page { items = List.copyOf(items); }
  }
}
