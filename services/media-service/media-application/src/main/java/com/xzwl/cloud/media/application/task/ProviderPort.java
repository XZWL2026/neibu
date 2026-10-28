package com.xzwl.cloud.media.application.task;

import com.xzwl.cloud.media.domain.task.*;
import java.util.*;

/** One selected provider only. A concrete adapter must recover/replay submission by operationId,
 * not blindly resubmit after an uncertain response. Credentials must come from environment.
 */
public interface ProviderPort {
  void requireAvailable(RouteSnapshot route);
  /** Validate supported inputs before charging; existing test providers retain their availability contract. */
  default void requireAvailable(TaskRequest request,RouteSnapshot route) { requireAvailable(route); }
  /** Owner-aware precharge validation binds any separately frozen portrait mappings. */
  default void requireAvailable(TaskRequest request,RouteSnapshot route,String owner) { requireAvailable(request,route); }
  /** Resolve file references with this persisted owner; do not treat a fileId as proof of access. */
  String submit(String operationId, String ownerId, TaskRequest request, RouteSnapshot route);
  ProviderPoll poll(String providerTaskId, TaskRequest request, RouteSnapshot route);

  /** Archive native outputs before an existing enhancement stage; never expose provider URLs. */
  default void archiveNative(String id,String owner,StoragePort storage) {}
  /** Archive diagnostic intermediate videos without adding final task results. */
  default void archiveEnhancement(String id,String owner,StoragePort storage) {}
  default Diagnostics diagnostics(String id) { return null; }
  record DiagnosticOutput(int index,String fileId,String failureCode) {}
  record Stage(String provider,String taskId,String status,String resolution,Long durationMs,Long videoTokens,
      String scheduleId,Long definitionId,String failureStage,String failureCode,List<DiagnosticOutput> outputs,List<EnhancementStep> steps) {
    public Stage(String provider,String taskId,String status,String resolution,Long durationMs,Long videoTokens,
        String scheduleId,Long definitionId,String failureStage,String failureCode,List<DiagnosticOutput> outputs) {
      this(provider,taskId,status,resolution,durationMs,videoTokens,scheduleId,definitionId,failureStage,failureCode,outputs,List.of());
    }
    public Stage { steps=steps==null?List.of():List.copyOf(steps); }
  }
  record EnhancementStep(int index,long definitionId,int width,int height,String status,Long durationMs,String fileId,String failureCode) {}
  /** Internal recovery data; source URLs must never enter administrator response records. */
  record EnhancementOutput(long definitionId,int width,int height,Long durationMs,String sourceUrl) {}
  record Diagnostics(Stage generation,Stage enhancement) {}

  default FailureDetail failure(String operationId) { return null; }
  record FailureDetail(String stage,String code,String status) {
    public FailureDetail(String stage,String code) { this(stage,code,null); }
  }

  record ProviderOutput(int index,String httpsUrl) {
    public ProviderOutput {
      if(index < 0 || index > 15) throw new IllegalArgumentException("invalid output index");
      MediaReference.https(httpsUrl);
    }
  }
  record ProviderPoll(Status status,List<ProviderOutput> outputs,String failureCode,String failureStatus,Long videoTokens,String resolution,List<EnhancementOutput> enhancementOutputs) {
    public ProviderPoll(Status status,List<ProviderOutput> outputs,String failureCode,String failureStatus,Long videoTokens,String resolution) { this(status,outputs,failureCode,failureStatus,videoTokens,resolution,List.of()); }
    public ProviderPoll(Status status,List<ProviderOutput> outputs,String failureCode,String failureStatus) { this(status,outputs,failureCode,failureStatus,null,null); }
    public ProviderPoll(Status status,List<ProviderOutput> outputs) { this(status,outputs,null,null); }
    public enum Status { PENDING, COMPLETED, FAILED }
    public ProviderPoll {
      Objects.requireNonNull(status); outputs=List.copyOf(outputs); enhancementOutputs=List.copyOf(enhancementOutputs);
      if (outputs.stream().map(ProviderOutput::index).distinct().count()!=outputs.size()
          || (status!=Status.COMPLETED && !outputs.isEmpty())) throw new IllegalArgumentException("invalid provider result");
    }
    public static ProviderPoll pending() { return new ProviderPoll(Status.PENDING,List.of()); }
    public static ProviderPoll failed(String code,String status) { return new ProviderPoll(Status.FAILED,List.of(),code,status); }
    public static ProviderPoll failed(String code) { return new ProviderPoll(Status.FAILED,List.of(),code,null); }
    public static ProviderPoll failed() { return new ProviderPoll(Status.FAILED,List.of()); }
    public static ProviderPoll completed(List<ProviderOutput> outputs) { return new ProviderPoll(Status.COMPLETED,outputs); }
  }
}
