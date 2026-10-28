package com.xzwl.cloud.media.infrastructure.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.media.application.provider.ArkConnectionStore;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.task.*;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Durable review receipts and paid-task asset bindings; no upstream secrets or response bodies are persisted. */
public final class JdbcPortraitReviews implements PortraitReviews {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final ArkConnectionStore connections;
  private final RoutingPort routing;
  private final StoragePort storage;
  private final VolcanoAssetClient client;
  private final MaasMediaAdapter maas;
  private final Clock clock;
  private final Map<String,String> env;
  private final Map<String,String> projectNames;
  private final org.springframework.transaction.support.TransactionTemplate transactions;
  public JdbcPortraitReviews(JdbcTemplate jdbc,ObjectMapper json,ArkConnectionStore connections,RoutingPort routing,
      StoragePort storage,ProviderHttp http,Clock clock,Map<String,String> env) {
    this.jdbc=jdbc; this.json=json; this.connections=connections; this.routing=routing; this.storage=storage;
    this.client=new VolcanoAssetClient(http,clock); this.clock=clock; this.env=Map.copyOf(env);
    var projects=new HashMap<String,String>();
    try {
      var configured=json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
          com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
          .readTree(env.getOrDefault("MEDIA_ASSET_PROJECT_NAMES","{}"));
      if(configured==null || !configured.isObject()) throw new IllegalArgumentException();
      for(var entry:configured.properties()) {
        MediaReference.identifier(entry.getKey(),"providerId");
        if(!entry.getValue().isTextual()) throw new IllegalArgumentException();
        String project=entry.getValue().textValue();
        MediaReference.identifier(project,"projectName");
        projects.put(entry.getKey(),project);
      }
    } catch(Exception invalid) { throw new IllegalArgumentException("invalid media asset project bindings"); }
    this.projectNames=Map.copyOf(projects);
    this.maas=new MaasMediaAdapter(http);
    this.transactions=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    // A competing unique insert must be visible when replaying its winner, including on MySQL's default RR.
    this.transactions.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
  }
  @Override public Portrait submit(String owner,String key,String file,String model,MediaReference.Kind requestedKind) {
    var kind=requestedKind==null?MediaReference.Kind.IMAGE:requestedKind;
    MediaReference.identifier(owner,"ownerId"); MediaReference.identifier(key,"Idempotency-Key");
    MediaReference.identifier(file,"fileId"); MediaReference.identifier(model,"model");
    String requestHash=VolcanoAssetClient.identity(owner,key);
    Row replay=byRequest(requestHash);
    if(replay!=null) return replay(owner,file,model,kind,replay);
    var selectedModel=reviewModel(model);
    // A lost create response cannot be circumvented by another key or credential rotation.
    Row unknown=jdbc.query("SELECT * FROM media_portrait WHERE owner_hash=? AND (status='UNCERTAIN' OR (status='SUBMITTING' AND stage IN ('GROUP_SENDING','ASSET_SENDING'))) ORDER BY created_at DESC,id DESC",
        this::row,ownerHash(owner)).stream().filter(r -> r.file.equals(file) && r.model.equals(model) && r.provider.equals(selectedModel.providerId())).findFirst().orElse(null);
    if(unknown!=null) return alias(requestHash,owner,file,model,kind,unknown);
    Snapshot config=snapshot(selectedModel);
    var media=resolve(owner,file,kind);
    String reuse=VolcanoAssetClient.identity(owner,file,model,config.provider,config.providerModel,config.generationIdentity,config.assetIdentity,config.project);
    return transactions.execute(transaction -> {
      Row selected=jdbc.query("SELECT * FROM media_portrait WHERE reuse_hash=? FOR UPDATE",this::row,reuse).stream().findFirst().orElse(null);
      if(selected!=null && selected.status.equals("FAILED")) {
        // Keep historical receipts and frozen task bindings; only the current reuse slot moves to a new attempt.
        jdbc.update("UPDATE media_portrait SET reuse_hash=? WHERE id=?",VolcanoAssetClient.identity("retired",selected.id),selected.id);
        selected=null;
      }
      boolean inserted=false;
      if(selected==null) {
        String id=UUID.randomUUID().toString(); long now=clock.millis();
        try {
          jdbc.update("INSERT INTO media_portrait (id,owner_hash,owner_id,file_id,model,reuse_hash,provider_id,provider_model,generation_identity,asset_identity,project_name,kind,duration_seconds,status,stage,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'SUBMITTING','GROUP_READY',?,?)",
              id,ownerHash(owner),owner,file,model,reuse,config.provider,config.providerModel,config.generationIdentity,config.assetIdentity,config.project,kind.name(),media.durationSeconds(),now,now);
          selected=find(id); inserted=true;
        } catch(DuplicateKeyException raced) {
          selected=jdbc.query("SELECT * FROM media_portrait WHERE reuse_hash=?",this::row,reuse).stream().findFirst().orElseThrow();
        }
      }
      Portrait actual=alias(requestHash,owner,file,model,kind,selected);
      if(inserted && !actual.id().equals(selected.id)) jdbc.update("DELETE FROM media_portrait WHERE id=?",selected.id);
      return actual;
    });
  }
  private Portrait alias(String hash,String owner,String file,String model,MediaReference.Kind kind,Row selected) {
    var portrait=replay(owner,file,model,kind,selected);
    try { jdbc.update("INSERT INTO media_portrait_request (request_hash,portrait_id) VALUES (?,?)",hash,selected.id); }
    catch(DuplicateKeyException raced) { return replay(owner,file,model,kind,Objects.requireNonNull(byRequest(hash))); }
    return portrait;
  }
  private Portrait replay(String owner,String file,String model,MediaReference.Kind kind,Row row) {
    if(!row.owner.equals(owner) || !row.file.equals(file) || !row.model.equals(model) || row.kind!=kind)
      throw new TaskConflictException("idempotency key already used with another portrait request");
    return project(row);
  }
  @Override public Portrait get(String owner,String id) {
    Row row=owned(owner,id);
    if(row.status.equals("PROCESSING") || row.status.equals("ACTIVE")) {
      try {
        Snapshot config=snapshot(row);
        if(matches(row,config)) refresh(row,config);
        else terminal(row,"FAILED");
      } catch(DefinitiveRejectionException rejected) { terminal(row,"FAILED"); }
      catch(RuntimeException unavailable) {
        return new Portrait(row.id,row.file,row.model,"PROCESSING","审核状态暂时不可用，请稍后重试",row.kind,row.durationSeconds);
      }
    }
    return currentProjection(owned(owner,id));
  }
  @Override public List<Portrait> list(String owner,int limit,String beforeId) {
    MediaReference.identifier(owner,"ownerId");
    if(limit<1 || limit>100) throw new IllegalArgumentException("limit must be 1..100");
    if(beforeId==null) return jdbc.query("SELECT * FROM media_portrait WHERE owner_hash=? ORDER BY created_at DESC,id DESC LIMIT ?",
        this::row,ownerHash(owner),limit).stream().map(this::currentProjection).toList();
    Row before=owned(owner,beforeId);
    return jdbc.query("SELECT * FROM media_portrait WHERE owner_hash=? AND (created_at<? OR (created_at=? AND id<?)) ORDER BY created_at DESC,id DESC LIMIT ?",
        this::row,ownerHash(owner),before.created,before.created,before.id,limit).stream().map(this::currentProjection).toList();
  }
  private Portrait currentProjection(Row row) {
    if(!row.status.equals("ACTIVE")) return project(row);
    try { if(matches(row,snapshot(row))) return project(row); }
    catch(RuntimeException unavailable) { /* Only the sanitized availability state crosses the public boundary. */ }
    return new Portrait(row.id,row.file,row.model,"FAILED","审核配置已变更，请重新上传审核",row.kind,row.durationSeconds);
  }
  @Override public void validate(String owner,TaskRequest request) {
    if(!hasPortraits(request)) return;
    Snapshot config=snapshot(request.model());
    checked(owner,request,config);
  }
  private List<Asset> checked(String owner,TaskRequest request,Snapshot config) {
    var assets=new ArrayList<Asset>();
    for(int i=0;i<request.references().size();i++) {
      var ref=request.references().get(i); if(ref.portraitId()==null) continue;
      Row row;
      try { row=owned(owner,ref.portraitId()); }
      catch(TaskNotFoundException missing) { throw new DefinitiveRejectionException("PORTRAIT_NOT_AVAILABLE"); }
      if(!row.file.equals(ref.fileId()) || row.kind!=ref.kind() || !row.status.equals("ACTIVE") || !matches(row,config))
        throw new DefinitiveRejectionException("PORTRAIT_NOT_AVAILABLE");
      requireActive(row,config);
      assets.add(new Asset(i,row.id,row.file,row.assetId,row.provider,config.providerModel,row.generationIdentity,row.assetIdentity,row.project,row.kind));
    }
    return List.copyOf(assets);
  }
  @Override public void freeze(String task,String owner,TaskRequest request,RouteSnapshot route) {
    if(!hasPortraits(request)) return;
    Snapshot config=snapshot(request.model());
    if(!route.providerId().equals(config.provider) || !route.providerModel().equals(config.providerModel)
        || !route.taskId().equals(task) || !route.requestedModel().equals(request.model()))
      throw new DefinitiveRejectionException("PORTRAIT_PROVIDER_MISMATCH");
    String mapping=encode(checked(owner,request,config));
    try { jdbc.update("INSERT INTO media_portrait_task (task_id,owner_hash,mapping_json) VALUES (?,?,?)",task,ownerHash(owner),mapping); }
    catch(DuplicateKeyException replay) {
      var existing=frozen(task,owner);
      if(!encode(existing).equals(mapping)) throw new DefinitiveRejectionException("PORTRAIT_MAPPING_CHANGED");
    }
  }
  /** Called immediately before Ark submission with the same generation settings used for that HTTP request. */
  AssetReferences assets(String task,String owner,TaskRequest resolved,RouteSnapshot route,ProviderSettings generation) {
    List<Asset> saved=frozen(task,owner);
    if(saved.isEmpty()) {
      PortraitReviews.rejectUnsupported(resolved);
      return new AssetReferences(Map.of(),null);
    }
    Snapshot config=snapshot(generation.arkConnection(),route.providerModel());
    var result=new HashMap<Integer,String>();
    for(Asset asset:saved) {
      if(!asset.provider.equals(route.providerId()) || !asset.providerModel.equals(route.providerModel())
          || !asset.generationIdentity.equals(config.generationIdentity) || !asset.assetIdentity.equals(config.assetIdentity)
          || !asset.project.equals(config.project) || asset.index<0 || asset.index>=resolved.references().size()
          || resolved.references().get(asset.index).kind()!=asset.kind || resolved.referenceMode()!=ReferenceMode.OMNI)
        throw new DefinitiveRejectionException("PORTRAIT_MAPPING_CHANGED");
      Row current=owned(owner,asset.portraitId);
      if(!current.file.equals(asset.fileId) || current.kind!=asset.kind || !Objects.equals(current.assetId,asset.assetId) || !matches(current,config))
        throw new DefinitiveRejectionException("PORTRAIT_MAPPING_CHANGED");
      requireActive(current,config);
      result.put(asset.index,asset.assetId);
    }
    return new AssetReferences(Map.copyOf(result),config.project);
  }
  private List<Asset> frozen(String task,String owner) {
    var values=jdbc.query("SELECT owner_hash,mapping_json FROM media_portrait_task WHERE task_id=?",(rs,n) -> {
      if(!rs.getString(1).equals(ownerHash(owner))) throw new DefinitiveRejectionException("PORTRAIT_MAPPING_CHANGED");
      try { return List.of(json.readValue(rs.getString(2),Asset[].class)); }
      catch(Exception invalid) { throw new DefinitiveRejectionException("PORTRAIT_MAPPING_CHANGED"); }
    },task);
    return values.isEmpty()?List.of():values.getFirst();
  }
  public void tick() {
    for(Row row:jdbc.query("SELECT * FROM media_portrait WHERE status IN ('SUBMITTING','PROCESSING') ORDER BY updated_at,id LIMIT 100",this::row)) {
      try {
        if(row.stage.endsWith("SENDING")) {
          // Five minutes exceeds the configured bounded provider request, and avoids racing an in-flight sender.
          if(row.updated<=clock.millis()-300_000) terminal(row,"UNCERTAIN");
          continue;
        }
        Snapshot config=snapshot(row);
        if(!matches(row,config)) { terminal(row,"FAILED"); continue; }
        if(row.status.equals("PROCESSING")) poll(row,config);
        else {
          var media=resolve(row.owner,row.file,row.kind);
          if(!Objects.equals(media.durationSeconds(),row.durationSeconds))
            throw new DefinitiveRejectionException("PORTRAIT_MEDIA_CHANGED");
          advance(row,config,media.httpsUrl());
        }
      } catch(DefinitiveRejectionException rejected) { terminal(row,"FAILED"); }
      catch(RuntimeException unavailable) { /* Safe reads and unstarted stages retry at the next scheduled scan. */ }
    }
  }
  private MediaReference resolve(String owner,String file,MediaReference.Kind kind) {
    var refs=storage.resolveReferences(owner,List.of(new MediaReference(file,null,kind,null)),ReferenceMode.OMNI);
    if(refs.size()!=1 || refs.getFirst().kind()!=kind || refs.getFirst().httpsUrl()==null
        || kind!=MediaReference.Kind.IMAGE && refs.getFirst().durationSeconds()==null)
      throw new DefinitiveRejectionException("INVALID_PORTRAIT_REFERENCE");
    var media=refs.getFirst();
    MediaReference.https(media.httpsUrl());
    return kind==MediaReference.Kind.IMAGE ? new MediaReference(null,media.httpsUrl()) : media;
  }
  private void advance(Row original,Snapshot config,String mediaUrl) {
    Row row=original;
    try {
      if(row.stage.equals("GROUP_READY")) {
        if(!stage(row,"GROUP_SENDING")) return;
        row=find(row.id);
        String group=identifier(assetCall(config,"CreateAssetGroup",
            Map.of("Name","portrait-"+row.id,"GroupType","AIGC","ProjectName",config.project)).path("Id").asText());
        jdbc.update("UPDATE media_portrait SET group_id=?,stage='ASSET_READY',updated_at=? WHERE id=? AND stage='GROUP_SENDING'",group,clock.millis(),row.id);
        row=find(row.id);
      }
      if(row.stage.equals("ASSET_READY")) {
        if(!stage(row,"ASSET_SENDING")) return;
        row=find(row.id);
        String asset=identifier(assetCall(config,"CreateAsset",
            Map.of("GroupId",row.groupId,"URL",mediaUrl,"AssetType",switch(row.kind) { case IMAGE -> "Image"; case VIDEO -> "Video"; case AUDIO -> "Audio"; },"ProjectName",config.project)).path("Id").asText());
        jdbc.update("UPDATE media_portrait SET asset_id=?,status='PROCESSING',stage='POLL',updated_at=? WHERE id=? AND stage='ASSET_SENDING'",
            asset,clock.millis(),row.id);
      }
    } catch(DefinitiveRejectionException rejected) { terminal(row,"FAILED"); }
    catch(RuntimeException unknown) { terminal(row,"UNCERTAIN"); }
  }
  private void requireActive(Row row,Snapshot config) {
    if(!row.status.equals("ACTIVE")) throw new DefinitiveRejectionException("PORTRAIT_NOT_AVAILABLE");
    if(!refresh(row,config).equals("ACTIVE")) throw new DefinitiveRejectionException("PORTRAIT_NOT_AVAILABLE");
  }
  private String refresh(Row row,Snapshot config) {
    var result=assetCall(config,"GetAsset",Map.of("Id",row.assetId,"ProjectName",config.project));
    if(!row.assetId.equals(result.path("Id").asText()) || !row.groupId.equals(result.path("GroupId").asText())
        || result.hasNonNull("ProjectName") && !row.project.equals(result.path("ProjectName").asText())
        || result.hasNonNull("AssetType") && !(switch(row.kind) { case IMAGE -> "Image"; case VIDEO -> "Video"; case AUDIO -> "Audio"; }).equals(result.path("AssetType").asText()))
      throw ProviderHttp.uncertain();
    String status=switch(result.path("Status").asText()) {
      case "Active" -> "ACTIVE";
      case "Processing" -> "PROCESSING";
      case "Failed" -> "FAILED";
      default -> throw ProviderHttp.uncertain();
    };
    if(!terminal(row,status)) throw ProviderHttp.uncertain();
    return status;
  }
  private void poll(Row row,Snapshot config) {
    try { refresh(row,config); }
    catch(DefinitiveRejectionException rejected) { terminal(row,"FAILED"); }
    catch(RuntimeException unavailable) {
      // Safe reads retry; an invalid receipt can never promote a review to ACTIVE.
      jdbc.update("UPDATE media_portrait SET updated_at=? WHERE id=? AND status='PROCESSING'",clock.millis(),row.id);
    }
  }
  private boolean stage(Row row,String stage) {
    return jdbc.update("UPDATE media_portrait SET stage=?,updated_at=? WHERE id=? AND stage=? AND status='SUBMITTING'",stage,clock.millis(),row.id,row.stage)==1;
  }
  private boolean terminal(Row row,String status) {
    return jdbc.update("UPDATE media_portrait SET status=?,updated_at=? WHERE id=? AND status=? AND stage=?",status,clock.millis(),row.id,row.status,row.stage)==1;
  }
  private RoutingPort.Model reviewModel(String model) {
    var selected=routing.models().stream().filter(m->m.modelId().equals(model)).findFirst()
        .orElseThrow(()->new DefinitiveRejectionException("PORTRAIT_MODEL_UNSUPPORTED"));
    if(selected.providerId()==null || selected.providerId().isBlank()) throw ProviderSettings.unavailable();
    return selected;
  }
  private Snapshot snapshot(String model) {
    return snapshot(reviewModel(model));
  }
  private Snapshot snapshot(RoutingPort.Model model) {
    return snapshot(connections.load(model.providerId()),model.providerModel());
  }
  private Snapshot snapshot(Row row) { return snapshot(connections.load(row.provider),row.providerModel); }
  private com.fasterxml.jackson.databind.JsonNode assetCall(Snapshot config,String action,Map<String,String> payload) {
    return MaasMediaAdapter.matches(config.endpoint) ? maas.asset(config.endpoint,config.access,config.providerModel,action,payload)
        : client.call(config.endpoint,config.access,config.secret,action,payload);
  }
  private Snapshot snapshot(ArkConnectionStore.Connection connection,String providerModel) {
    String provider=connection.providerId(), access=value("MEDIA_ASSET_ACCESS_KEY_ID"), secret=value("MEDIA_ASSET_SECRET_ACCESS_KEY"),
        project=projectNames.getOrDefault(provider,value("MEDIA_ASSET_PROJECT_NAME"));
    if(OmniReference.forModel(providerModel)==null) throw new DefinitiveRejectionException("PORTRAIT_MODEL_UNSUPPORTED");
    if(MaasMediaAdapter.matches(connection.baseUrl())) {
      if(connection.apiKey().isBlank() || !MaasMediaAdapter.supports(providerModel)) throw ProviderSettings.unavailable();
      String endpoint=ProviderSettings.arkEndpoint(connection.baseUrl()).toString();
      if(project.isBlank()) project="default";
      MediaReference.identifier(project,"projectName");
      return new Snapshot(provider,providerModel,project,endpoint,connection.apiKey(),"",
          VolcanoAssetClient.identity(provider,endpoint,connection.apiKey()),
          VolcanoAssetClient.identity(endpoint,connection.apiKey(),project));
    }
    if(provider.isBlank() || access.isBlank() || secret.isBlank() || project.isBlank() || connection.apiKey().isBlank()) throw ProviderSettings.unavailable();
    MediaReference.identifier(provider,"providerId"); MediaReference.identifier(project,"projectName");
    URI generation=ProviderSettings.arkEndpoint(connection.baseUrl());
    if(!provider.equals(connection.providerId()) || !generation.getScheme().equals("https")
        || !generation.getHost().equals("ark.cn-beijing.volces.com") || generation.getPort()!=-1
        || !generation.getPath().equals("/api/v3")) throw new DefinitiveRejectionException("PORTRAIT_PROVIDER_UNSUPPORTED");
    String endpoint=env.getOrDefault("MEDIA_ASSET_ENDPOINT","https://ark.cn-beijing.volcengineapi.com").replaceAll("/+$","");
    URI asset=ProviderSettings.endpoint(endpoint);
    boolean fixture=asset.getScheme().equals("http") && asset.getHost().equals("127.0.0.1");
    if(!fixture && !(asset.getScheme().equals("https") && asset.getHost().equals("ark.cn-beijing.volcengineapi.com") && (asset.getPort()==-1 || asset.getPort()==443))
        || !asset.getPath().isEmpty()) throw ProviderSettings.unavailable();
    return new Snapshot(provider,providerModel,project,endpoint,access,secret,
        VolcanoAssetClient.identity(provider,generation.toString(),connection.apiKey()),
        VolcanoAssetClient.identity(endpoint,access,secret,project));
  }
  private String value(String key) { return env.getOrDefault(key,"").trim(); }
  private boolean matches(Row row,Snapshot config) {
    // Review provenance may name another compatible model; the frozen task binds its actual target model.
    return row.provider.equals(config.provider)
        && row.generationIdentity.equals(config.generationIdentity) && row.assetIdentity.equals(config.assetIdentity) && row.project.equals(config.project);
  }
  private static boolean hasPortraits(TaskRequest request) { return request.references().stream().anyMatch(r -> r.portraitId()!=null); }
  private static String ownerHash(String owner) { return VolcanoAssetClient.identity(owner); }
  private String encode(Object value) {
    try { return json.writeValueAsString(value); }
    catch(Exception invalid) { throw new IllegalArgumentException("invalid portrait mapping"); }
  }
  private Row owned(String owner,String id) {
    MediaReference.identifier(owner,"ownerId"); MediaReference.identifier(id,"portraitId");
    Row row=find(id);
    if(row==null || !row.owner.equals(owner) || !row.id.equals(id)) throw new TaskNotFoundException("portrait not found");
    return row;
  }
  private Row byRequest(String hash) {
    return jdbc.query("SELECT p.* FROM media_portrait p JOIN media_portrait_request r ON p.id=r.portrait_id WHERE r.request_hash=?",this::row,hash).stream().findFirst().orElse(null);
  }
  private Row find(String id) { return jdbc.query("SELECT * FROM media_portrait WHERE id=?",this::row,id).stream().findFirst().orElse(null); }
  private Row row(ResultSet rs,int index) throws SQLException {
    return new Row(rs.getString("id"),rs.getString("owner_id"),rs.getString("file_id"),rs.getString("model"),rs.getString("provider_id"),rs.getString("provider_model"),
        rs.getString("generation_identity"),rs.getString("asset_identity"),rs.getString("project_name"),rs.getString("status"),rs.getString("stage"),
        rs.getString("group_id"),rs.getString("asset_id"),rs.getLong("created_at"),rs.getLong("updated_at"),MediaReference.Kind.valueOf(rs.getString("kind")),rs.getObject("duration_seconds",Double.class));
  }
  private static String identifier(String id) { return ArkMediaAdapter.taskId(id); }
  private Portrait project(Row row) {
    return new Portrait(row.id,row.file,row.model,row.status,switch(row.status) {
      case "FAILED" -> "人像审核失败，可重新审核";
      case "UNCERTAIN" -> "审核提交结果待确认，请勿重复提交";
      default -> null;
    },row.kind,row.durationSeconds);
  }
  private record Row(String id,String owner,String file,String model,String provider,String providerModel,String generationIdentity,
      String assetIdentity,String project,String status,String stage,String groupId,String assetId,long created,long updated,MediaReference.Kind kind,Double durationSeconds) {}
  record AssetReferences(Map<Integer,String> ids,String project) {}
  record Asset(int index,String portraitId,String fileId,String assetId,String provider,String providerModel,String generationIdentity,String assetIdentity,String project,MediaReference.Kind kind) {
    Asset { kind=kind==null?MediaReference.Kind.IMAGE:kind; }
  }
  private record Snapshot(String provider,String providerModel,String project,String endpoint,String access,String secret,String generationIdentity,String assetIdentity) {
    @Override public String toString() { return "PortraitSnapshot[REDACTED]"; }
  }
}
