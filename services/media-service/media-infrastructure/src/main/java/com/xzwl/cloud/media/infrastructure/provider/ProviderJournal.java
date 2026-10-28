package com.xzwl.cloud.media.infrastructure.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.media.application.task.ProviderPort.ProviderOutput;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Each intent CAS commits before the external POST. SENDING is never reset after an ambiguous response. */
public final class ProviderJournal {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  public ProviderJournal(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
  public ProviderSettings.Plan executionPlan(String id) { var operation=find(id);return operation==null?null:operation.plan(); }
  void httpDiagnostic(String id,String phase,String detail) {
    var log=org.slf4j.LoggerFactory.getLogger(ProviderJournal.class);
    try {
      jdbc.update("INSERT INTO media_provider_http_diagnostic(id,operation_id,phase,created_at,detail_json) VALUES(?,?,?,?,?)",
          UUID.randomUUID().toString(),id,phase,System.currentTimeMillis(),detail);
      log.info("provider_http_diagnostic {}",detail);
    } catch(RuntimeException unavailable) {
      // Keep evidence in the service journal without turning an accepted paid POST into a retry.
      log.error("provider_http_diagnostic_persistence_failed {}",detail);
    }
  }
  Operation find(String id) {
    return jdbc.query("SELECT * FROM media_provider_operation WHERE operation_id=?",(row,index)->new Operation(
        row.getString("operation_id"),row.getString("phase"),decode(row.getString("plan_json"),ProviderSettings.Plan.class),
        row.getString("upstream_id"),row.getString("source_url"),outputs(row.getString("outputs_json")),row.getString("failure_stage"),row.getString("failure_code"),row.getString("failure_status"),
        row.getString("generation_task_id"),row.getString("enhancement_task_id"),
        row.getObject("generation_started_at",Long.class),row.getObject("generation_finished_at",Long.class),
        row.getObject("enhancement_started_at",Long.class),row.getObject("enhancement_finished_at",Long.class),
        row.getObject("video_tokens",Long.class),row.getString("native_resolution"),nativeOutputs(row.getString("native_outputs_json")),enhancementSteps(row.getString("enhancement_steps_json"))),id).stream().findFirst().orElse(null);
  }
  Operation create(String id,ProviderSettings.Plan plan) {
    jdbc.update("INSERT INTO media_provider_operation(operation_id,phase,plan_json) VALUES(?,'ARK_READY',?) ON DUPLICATE KEY UPDATE operation_id=operation_id",id,encode(plan));
    return Objects.requireNonNull(find(id));
  }
  Operation createEnhancement(String id,ProviderSettings.Plan plan) {
    jdbc.update("INSERT INTO media_provider_operation(operation_id,phase,plan_json) VALUES(?,'MPS_READY',?) ON DUPLICATE KEY UPDATE operation_id=operation_id",id,encode(plan));
    return Objects.requireNonNull(find(id));
  }
  boolean move(Operation old,String phase,String upstream,String source,List<ProviderOutput> outputs) {
    return move(old,phase,upstream,source,outputs,null,null);
  }
  boolean move(Operation old,String phase,String upstream,String source,List<ProviderOutput> outputs,Long tokens,String resolution) {
    return move(old,phase,upstream,source,outputs,tokens,resolution,List.of());
  }
  boolean move(Operation old,String phase,String upstream,String source,List<ProviderOutput> outputs,Long tokens,String resolution,
      List<com.xzwl.cloud.media.application.task.ProviderPort.EnhancementOutput> enhancements) {
    long now=System.currentTimeMillis();
    boolean generated=phase.equals("MPS_READY") || phase.equals("COMPLETE") && old.phase().startsWith("ARK_");
    return jdbc.update("UPDATE media_provider_operation SET phase=?,upstream_id=?,source_url=?,outputs_json=?,"
        +"generation_task_id=COALESCE(generation_task_id,?),enhancement_task_id=COALESCE(enhancement_task_id,?),"
        +"generation_started_at=COALESCE(generation_started_at,?),generation_finished_at=COALESCE(generation_finished_at,?),"
        +"enhancement_started_at=COALESCE(enhancement_started_at,?),enhancement_finished_at=COALESCE(enhancement_finished_at,?),"
        +"video_tokens=COALESCE(video_tokens,?),native_resolution=COALESCE(?,native_resolution),"
        +"native_outputs_json=COALESCE(native_outputs_json,?),enhancement_steps_json=COALESCE(enhancement_steps_json,?) WHERE operation_id=? AND phase=?",
        phase,upstream,source,outputs==null?null:encode(outputs),
        phase.equals("ARK_RUNNING")?upstream:old.phase().equals("ARK_RUNNING")?old.upstream():null,
        phase.equals("MPS_RUNNING")?upstream:old.phase().equals("MPS_RUNNING")?old.upstream():null,
        phase.equals("ARK_SENDING")?now:null,generated?now:null,
        phase.equals("MPS_SENDING")?now:null,phase.equals("COMPLETE") && old.phase().startsWith("MPS_")?now:null,
        generated?tokens:null,resolution,
        phase.equals("MPS_READY")?encode(outputs.stream().map(o->new NativeOutput(o.index(),o.httpsUrl(),null,null)).toList()):null,
        enhancements.isEmpty()?null:encode(enhancements.stream().map(o->new EnhancementStep(o,null,o.sourceUrl()==null?"INVALID_ENHANCEMENT_OUTPUT":null)).toList()),
        old.id(),old.phase())==1;
  }
  boolean fail(Operation old,String code) { return fail(old,code,null); }
  boolean fail(Operation old,String code,String status) {
    return jdbc.update("UPDATE media_provider_operation SET phase='FAILED',failure_stage=?,failure_code=?,failure_status=?,"
        +"generation_finished_at=COALESCE(generation_finished_at,?),enhancement_finished_at=COALESCE(enhancement_finished_at,?),"
        +"generation_task_id=COALESCE(generation_task_id,?),enhancement_task_id=COALESCE(enhancement_task_id,?) WHERE operation_id=? AND phase=?",
        old.phase(),code,status,old.phase().startsWith("ARK_")?System.currentTimeMillis():null,
        old.phase().startsWith("MPS_")?System.currentTimeMillis():null,
        old.phase().equals("ARK_RUNNING")?old.upstream():null,old.phase().equals("MPS_RUNNING")?old.upstream():null,old.id(),old.phase())==1;
  }
  void saveNative(Operation old,List<NativeOutput> outputs) {
    jdbc.update("UPDATE media_provider_operation SET native_outputs_json=? WHERE operation_id=? AND phase=?",
        encode(outputs),old.id(),old.phase());
  }
  void saveEnhancements(Operation old,List<EnhancementStep> steps) {
    jdbc.update("UPDATE media_provider_operation SET enhancement_steps_json=? WHERE operation_id=? AND phase=?",encode(steps),old.id(),old.phase());
  }
  private List<EnhancementStep> enhancementSteps(String raw) { return raw==null?List.of():List.of(decode(raw,EnhancementStep[].class)); }
  private List<NativeOutput> nativeOutputs(String raw) { return raw==null?List.of():List.of(decode(raw,NativeOutput[].class)); }
  private List<ProviderOutput> outputs(String raw) { return raw==null?List.of():List.of(decode(raw,ProviderOutput[].class)); }
  private String encode(Object value) {
    try { return json.writeValueAsString(value); }
    catch(Exception invalid) { throw new IllegalStateException("cannot encode provider recovery state"); }
  }
  private <T> T decode(String value,Class<T> type) {
    try { return json.readValue(value,type); }
    catch(Exception invalid) { throw new IllegalStateException("cannot decode provider recovery state"); }
  }
  record Operation(String id,String phase,ProviderSettings.Plan plan,String upstream,String source,List<ProviderOutput> outputs,String failureStage,String failureCode,String failureStatus,
      String generationId,String enhancementId,Long generationStarted,Long generationFinished,Long enhancementStarted,
      Long enhancementFinished,Long tokens,String resolution,List<NativeOutput> nativeOutputs,List<EnhancementStep> enhancementSteps) {}
  record EnhancementStep(com.xzwl.cloud.media.application.task.ProviderPort.EnhancementOutput output,String fileId,String failureCode) {}
  record NativeOutput(int index,String sourceUrl,String fileId,String failureCode) {}
}
