package com.xzwl.cloud.operations.application.readmodel;

import com.xzwl.cloud.operations.application.readmodel.OperationsStore.*;
import java.util.Objects;

/** Validates projection/query inputs without coupling the use cases to JDBC or HTTP. */
public final class OperationsQueryService {
  private final OperationsStore store;

  public OperationsQueryService(OperationsStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public OperationalRecord project(Projection projection) {
    Objects.requireNonNull(projection, "projection");
    id(projection.eventId()); id(projection.recordId()); id(projection.userId());
    Objects.requireNonNull(projection.type(), "type");
    text(projection.status()); text(projection.summary()); text(projection.detailsJson());
    Objects.requireNonNull(projection.occurredAt(), "occurredAt");
    if (projection.amount() != null && projection.amount().precision() - projection.amount().scale() > 34) {
      throw new IllegalArgumentException("amount exceeds DECIMAL(38,4)");
    }
    if (projection.sourceVersion() < 1) throw new IllegalArgumentException("sourceVersion must be positive");
    return store.project(projection);
  }

  public OperationalRecord get(RecordType type, String recordId) {
    return store.get(Objects.requireNonNull(type, "type"), id(recordId));
  }

  public Page search(SearchFilter filter) {
    Objects.requireNonNull(filter, "filter");
    if (filter.page() < 0 || filter.size() < 1 || filter.size() > 100) {
      throw new IllegalArgumentException("invalid page");
    }
    if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
      throw new IllegalArgumentException("from must not be after to");
    }
    if (filter.minimumAmount() != null && filter.maximumAmount() != null
        && filter.minimumAmount().compareTo(filter.maximumAmount()) > 0) {
      throw new IllegalArgumentException("minimumAmount must not exceed maximumAmount");
    }
    return store.search(filter);
  }

  private static String id(String value) {
    if (value == null || value.isBlank() || value.length() > 128) {
      throw new IllegalArgumentException("id must be 1..128 characters");
    }
    return value;
  }
  private static void text(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("text must not be blank");
  }
}
