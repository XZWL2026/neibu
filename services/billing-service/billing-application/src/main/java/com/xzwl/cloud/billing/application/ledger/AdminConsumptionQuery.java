package com.xzwl.cloud.billing.application.ledger;
import java.time.Instant;
import java.util.List;

public interface AdminConsumptionQuery {
  Page search(Query query,String cookie);
  Analytics analytics(Query query,String cookie);
  Detail detail(String id,String cookie);
  Access access(String id,String fileId,String cookie);
  record Query(String userId,String type,String model,String status,Instant from,Instant to,int offset,int limit) {}
  record User(String id,String nickname,String phone) {}
  record Item(ConsumptionStore.Row ledger,User user,String currentAccountType,boolean keyEnabled,String taskStatus,String modelName) {}
  record Page(List<Item> items,long total,int offset,int limit) {}
  record Analytics(long total,String amount4,long unknownAmounts,List<ModelTotal> models,List<DayTotal> days) {}
  record ModelTotal(String model,String modelName,String type,long calls,Long quantity,String amount4,List<ResolutionTotal> resolutions) {}
  record ResolutionTotal(String resolution,long calls,Long quantity,String amount4) {}
  record DayTotal(String date,long calls,String amount4,String videoAmount4,String imageAmount4) {}
  record Detail(Item item,List<ConsumptionStore.Entry> entries,Snapshot snapshot) {}
  record Access(String url,Instant expiresAt) {}
  record Snapshot(String ownerId,Task task,List<Reference> references,String expectedRefundPoints4,Stage generation,Stage enhancement) {
    public Snapshot(String ownerId,Task task,List<Reference> references,String expectedRefundPoints4) { this(ownerId,task,references,expectedRefundPoints4,null,null); }
  }
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
  record Reference(int index,String fileId,String kind) {}
  record Output(int index,String fileId) {}
  record Failure(int index,String message) {}
  record Task(String id,String status,String type,String model,String modelName,String prompt,String aspectRatio,
      String resolution,int count,int durationSeconds,Instant createdAt,Instant deadlineAt,List<Output> results,
      List<Failure> failures,String failureMessage,String refundStatus,boolean referenceFollowsFirstFrame) {}
}
