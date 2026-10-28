package com.xzwl.cloud.media.infrastructure.suite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.media.application.suite.ProductSuites;
import com.xzwl.cloud.media.application.suite.ProductSuites.Module;
import com.xzwl.cloud.media.application.catalog.GenerationCatalogService;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.media.domain.task.*;
import com.xzwl.cloud.media.infrastructure.provider.ProductCopyWriter;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcProductSuites implements ProductSuites {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;
  private final MediaTaskService tasks;
  private final GenerationCatalogService catalog;
  private final StoragePort storage;
  private final Clock clock;
  private final ProductCopyWriter copyWriter;
  public JdbcProductSuites(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager transactions,MediaTaskService tasks,
      GenerationCatalogService catalog,StoragePort storage,Clock clock) {
    this(jdbc,json,transactions,tasks,catalog,storage,clock,null);
  }
  public JdbcProductSuites(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager transactions,MediaTaskService tasks,
      GenerationCatalogService catalog,StoragePort storage,Clock clock,ProductCopyWriter copyWriter) {
    this.jdbc=jdbc;this.json=json;this.tasks=tasks;this.catalog=catalog;this.storage=storage;this.clock=clock;this.copyWriter=copyWriter;
    transaction=new TransactionTemplate(transactions);
    transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
  }
  public List<Template> templates() { return TEMPLATES; }
  public boolean copyAvailable() { return copyWriter!=null && copyWriter.configured(); }
  public Copy copy(String owner,CopyRequest request) {
    var resolved=prepareAssistance(owner,request);
    return new Copy(copyWriter.write(resolved,request.productName(),request.sellingPoints(),request.language()));
  }
  public Plan plan(String owner,CopyRequest request) {
    var resolved=prepareAssistance(owner,request);
    return copyWriter.plan(resolved,request.productName(),request.sellingPoints(),request.language());
  }
  public VideoBreakdown videoBreakdown(String owner,VideoBreakdownRequest request) {
    var resolved=prepareAssistance(owner,new CopyRequest(request.productName(),request.sellingPoints(),request.language(),request.fileIds()));
    return copyWriter.videoBreakdown(resolved,request);
  }
  private List<MediaReference> prepareAssistance(String owner,CopyRequest request) {
    MediaReference.identifier(owner,"ownerId");
    if(!copyAvailable()) throw new DependencyUnavailableException("product copy is not configured");
    return storage.resolveReferences(owner,request.fileIds().stream().map(id->new MediaReference(id,null)).toList());
  }
  public Quote quote(String owner,Request request) {
    var template=template(request);validate(owner,request);
    List<ModuleQuote> quotes=new ArrayList<>();BigDecimal total=BigDecimal.ZERO;
    for(var module:request.modules()) {
      BigDecimal points=catalog.quote(owner,request.task(module,template));total=total.add(points);
      quotes.add(new ModuleQuote(module,points.setScale(4).toPlainString()));
    }
    return new Quote(total.setScale(4).toPlainString(),List.copyOf(quotes));
  }
  public Suite submit(String owner,String key,Request request) {
    identity(owner,key);var template=template(request);
    var existing=byKey(owner,key);if(existing!=null) return replay(existing,request);
    var option=validate(owner,request);
    return transaction.execute(tx->{
      String id=UUID.randomUUID().toString();
      try { jdbc.update("INSERT INTO media_product_suite (id,owner_id,request_key,request_json,created_at) VALUES (?,?,?,?,?)",id,owner,key,encode(request),clock.millis()); }
      catch(DuplicateKeyException raced) { return replay(Objects.requireNonNull(byKey(owner,key)),request); }
      for(var module:request.modules()) {
        var task=tasks.submit(owner,"suite-"+id+"-"+module.name(),request.task(module,template),new TaskData.Presentation(option.displayName(),false));
        jdbc.update("INSERT INTO media_product_suite_item (suite_id,module,attempt,task_id) VALUES (?,?,0,?)",id,module.name(),task.id());
      }
      return get(owner,id);
    });
  }
  public Suite regenerate(String owner,String id,Module module,String sourceId,String key) {
    identity(owner,key);MediaReference.identifier(id,"suiteId");MediaReference.identifier(sourceId,"sourceTaskId");
    var original=get(owner,id);
    if(module==null || !original.request().modules().contains(module)) throw new IllegalArgumentException("module not selected");
    String taskKey="suite-"+id+"-"+key;
    var previous=tasks.findRequest(owner,taskKey);
    if(previous.isPresent()) return replayRegeneration(owner,id,module,sourceId,previous.get().id());
    var option=validate(owner,original.request());
    return transaction.execute(tx->{
      var locked=jdbc.query("SELECT * FROM media_product_suite WHERE id=? AND owner_id=? FOR UPDATE",this::read,id,owner);
      if(locked.isEmpty()) throw new TaskNotFoundException("suite not found");
      var replay=tasks.findRequest(owner,taskKey);
      if(replay.isPresent()) return replayRegeneration(owner,id,module,sourceId,replay.get().id());
      var suite=get(owner,id);var item=suite.items().stream().filter(i->i.module()==module).findFirst().orElseThrow();
      if(!item.taskIds().getLast().equals(sourceId) || !tasks.get(owner,sourceId).status().terminal())
        throw new TaskConflictException("module changed or still running");
      // Keep the source task and its files; the existing independent-regeneration method hides its history.
      var task=tasks.submit(owner,taskKey,tasks.get(owner,sourceId).data().request(),new TaskData.Presentation(option.displayName(),false));
      jdbc.update("INSERT INTO media_product_suite_item (suite_id,module,attempt,task_id,source_task_id) VALUES (?,?,?,?,?)",id,module.name(),item.taskIds().size(),task.id(),sourceId);
      return get(owner,id);
    });
  }
  private Suite replayRegeneration(String owner,String id,Module module,String sourceId,String taskId) {
    Integer matches=jdbc.queryForObject("SELECT COUNT(*) FROM media_product_suite_item WHERE suite_id=? AND module=? AND source_task_id=? AND task_id=?",Integer.class,id,module.name(),sourceId,taskId);
    if(matches==null || matches!=1) throw new TaskConflictException("idempotency key belongs to another module retry");
    return get(owner,id);
  }
  public Suite get(String owner,String id) {
    MediaReference.identifier(owner,"ownerId");MediaReference.identifier(id,"suiteId");
    return jdbc.query("SELECT * FROM media_product_suite WHERE id=? AND owner_id=?",this::read,id,owner).stream().findFirst().orElseThrow(()->new TaskNotFoundException("suite not found"));
  }
  public List<Suite> list(String owner,int limit,String beforeId) {
    MediaReference.identifier(owner,"ownerId");
    if(limit<1 || limit>20) throw new IllegalArgumentException("limit must be 1..20");
    if(beforeId==null) return jdbc.query("SELECT * FROM media_product_suite WHERE owner_id=? ORDER BY created_at DESC,id DESC LIMIT ?",this::read,owner,limit);
    var before=get(owner,beforeId);long time=before.createdAt().toEpochMilli();
    return jdbc.query("SELECT * FROM media_product_suite WHERE owner_id=? AND (created_at<? OR (created_at=? AND id<?)) ORDER BY created_at DESC,id DESC LIMIT ?",this::read,owner,time,time,beforeId,limit);
  }
  private GenerationCatalogService.Option validate(String owner,Request request) {
    MediaReference.identifier(owner,"ownerId");
    var option=catalog.options(owner).stream().filter(o->o.type()==MediaType.IMAGE && o.model().equals(request.model())
        && o.maxReferenceImages()>=request.fileIds().size() && o.aspectRatios().contains(request.aspectRatio())
        && o.resolutions().contains(request.resolution())).findFirst().orElseThrow(()->new DefinitiveRejectionException("UNSUPPORTED_SUITE_MODEL"));
    var template=template(request);
    for(var module:request.modules()) catalog.validate(owner,request.task(module,template));
    storage.resolveReferences(owner,request.references());
    return option;
  }
  private Template template(Request request) {
    return ProductSuites.template(request);
  }
  private Suite byKey(String owner,String key) {
    return jdbc.query("SELECT * FROM media_product_suite WHERE owner_id=? AND request_key=?",this::read,owner,key).stream().findFirst().orElse(null);
  }
  private Suite replay(Suite suite,Request request) {
    if(!suite.request().equals(request)) throw new TaskConflictException("idempotency key already used with another suite request");return suite;
  }
  private Suite read(ResultSet row,int n) throws SQLException {
    String id=row.getString("id");Request request;
    try { request=json.readValue(row.getString("request_json"),Request.class); }
    catch(Exception invalid) { throw new IllegalStateException("cannot read product suite",invalid); }
    var items=request.modules().stream().map(module->new Item(module,jdbc.query("SELECT task_id FROM media_product_suite_item WHERE suite_id=? AND module=? ORDER BY attempt",(rs,i)->rs.getString(1),id,module.name()))).toList();
    return new Suite(id,Instant.ofEpochMilli(row.getLong("created_at")),request,items);
  }
  private String encode(Request request) {
    try { return json.writeValueAsString(request); }catch(Exception invalid) { throw new IllegalArgumentException("invalid suite request"); }
  }
  private static void identity(String owner,String key) {
    MediaReference.identifier(owner,"ownerId");MediaReference.identifier(key,"Idempotency-Key");
    if(key.length()>64) throw new IllegalArgumentException("request key too long");
  }
}
