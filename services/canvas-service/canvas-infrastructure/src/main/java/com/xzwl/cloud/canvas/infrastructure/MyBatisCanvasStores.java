package com.xzwl.cloud.canvas.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.canvas.application.*;
import com.xzwl.cloud.canvas.domain.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Each view carries its owner into every read and compare-and-swap SQL predicate. */
public final class MyBatisCanvasStores implements OwnerCanvasStores {
  private final CanvasMapper documents;
  private final CanvasVersionMapper versions;
  private final CanvasMediaReferenceMapper references;
  private final ObjectMapper json;
  private final TransactionTemplate transactions;
  public MyBatisCanvasStores(CanvasMapper documents, CanvasVersionMapper versions,
      CanvasMediaReferenceMapper references, ObjectMapper json, TransactionTemplate transactions) {
    this.documents = documents; this.versions = versions; this.references = references;
    this.json = json; this.transactions = transactions;
  }
  @Override public CanvasStore forOwner(String ownerId) {
    CanvasInputPolicy.identifier(ownerId);
    return new CanvasStore() {
      @Override public Optional<Canvas> findById(String id) {
        CanvasInputPolicy.identifier(id);
        var row = documents.selectOne(new QueryWrapper<CanvasRow>().eq("id", id).eq("owner_id", ownerId).eq("deleted", false));
        if (row == null) return Optional.empty();
        return Optional.of(restore(row));
      }
      @Override public List<Canvas> findPage(int offset, int limit) {
        return documents.findPage(ownerId, offset, limit).stream().map(MyBatisCanvasStores.this::restore).toList();
      }
      @Override public void save(Canvas canvas) {
        CanvasRow row = row(canvas, ownerId);
        if (row.version != 1 || row.deleted) throw new IllegalArgumentException("Creation requires version one");
        transactions.executeWithoutResult(tx -> { documents.insert(row); history(canvas, row); });
      }
      @Override public void saveIfVersion(Canvas canvas, long expectedVersion) {
        CanvasRow row = row(canvas, ownerId);
        if (expectedVersion < 1 || row.version != expectedVersion + 1) throw new IllegalArgumentException("Invalid next version");
        transactions.executeWithoutResult(tx -> {
          if (documents.compareAndSave(row, expectedVersion) != 1) throw new IllegalStateException("Canvas version conflict");
          history(canvas, row);
        });
      }
    };
  }
  private Canvas restore(CanvasRow row) {
    try {
      return Canvas.restore(row.id, row.ownerId, row.name, json.readValue(row.contentJson, CanvasContent.class),
          row.version, row.createdAt.toInstant(), row.updatedAt.toInstant(), row.deleted);
    } catch (JsonProcessingException failure) {
      throw new org.springframework.dao.DataRetrievalFailureException("Stored Canvas JSON is invalid", failure);
    }
  }
  private CanvasRow row(Canvas canvas, String ownerId) {
    if (!ownerId.equals(canvas.ownerId())) throw new IllegalArgumentException("Owner mismatch");
    CanvasInputPolicy.identifier(canvas.id()); CanvasInputPolicy.name(canvas.name()); CanvasInputPolicy.content(canvas.content());
    var row = new CanvasRow();
    row.id = canvas.id(); row.ownerId = ownerId; row.name = canvas.name(); row.version = canvas.version();
    row.createdAt = Timestamp.from(canvas.createdAt()); row.updatedAt = Timestamp.from(canvas.updatedAt()); row.deleted = canvas.deleted();
    try { row.contentJson = json.writeValueAsString(canvas.content()); }
    catch (JsonProcessingException failure) { throw new IllegalArgumentException("Invalid canvas JSON", failure); }
    if (row.contentJson.getBytes(StandardCharsets.UTF_8).length > 1_048_576) throw new IllegalArgumentException("Canvas exceeds 1 MiB");
    return row;
  }
  private void history(Canvas canvas, CanvasRow row) {
    var version = new CanvasVersionRow();
    version.id = UUID.randomUUID().toString(); version.canvasId = row.id; version.version = row.version;
    version.contentJson = row.contentJson; version.deleted = row.deleted; version.savedAt = row.updatedAt;
    versions.insert(version);
    for (var node : canvas.content().nodes()) for (var property : node.properties().entrySet()) {
      if (!CanvasInputPolicy.REFERENCE_KEYS.contains(property.getKey())) continue;
      var ref = new CanvasMediaReferenceRow();
      ref.id = UUID.randomUUID().toString(); ref.canvasId = row.id; ref.version = row.version;
      ref.nodeId = node.id(); ref.nodeKind = node.kind(); ref.propertyName = property.getKey(); ref.referenceValue = property.getValue();
      references.insert(ref);
    }
  }
}
