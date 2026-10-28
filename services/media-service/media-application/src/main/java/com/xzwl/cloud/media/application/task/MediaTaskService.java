package com.xzwl.cloud.media.application.task;

import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.media.domain.task.*;
import java.time.*;
import java.util.*;

public final class MediaTaskService {
  private final MediaTaskStore store;
  private final Clock clock;
  private final TaskPolicy policy;
  public MediaTaskService(MediaTaskStore store,Clock clock,TaskPolicy policy) {
    this.store=store; this.clock=clock; this.policy=policy;
  }
  public MediaTask submit(String owner,String key,TaskRequest request) {
    return submit(owner,key,request,null);
  }
  public MediaTask submit(String owner,String key,TaskRequest request,TaskData.Presentation presentation) {
    MediaReference.identifier(owner,"ownerId"); MediaReference.identifier(key,"Idempotency-Key");
    Objects.requireNonNull(request);
    MediaTask candidate=candidate(owner,key,request,presentation);
    MediaTask actual=store.create(candidate);
    if(!actual.data().request().equals(request)) throw new TaskConflictException("idempotency key already used with another request");
    return actual;
  }
  private MediaTask candidate(String owner,String key,TaskRequest request,TaskData.Presentation presentation) {
    Instant now=clock.instant();
    return new MediaTask(UUID.randomUUID().toString(),owner,key,TaskStatus.ROUTING,
        new TaskData(request,null,null,null,null,null,null).withPresentation(presentation), now,
        now.plus(request.type()==MediaType.IMAGE ? policy.imageTimeout():policy.videoTimeout()),now,0);
  }
  public MediaTask historySource(String owner,String id) {
    MediaTask task=get(owner,id);
    if(!task.status().terminal() || task.data().request().operation()!=TaskOperation.GENERATION
        || task.idempotencyKey().startsWith("canvas-") || task.idempotencyKey().startsWith("suite-")) throw new TaskConflictException("task is not an independent terminal generation");
    return task;
  }
  public void deleteHistory(String owner,String id) {
    historySource(owner,id); store.deleteHistory(owner,id);
  }
  public Optional<MediaTask> replayRegeneration(String owner,String sourceId,String key) {
    MediaReference.identifier(owner,"ownerId"); MediaReference.identifier(key,"Idempotency-Key");
    if(key.startsWith("canvas-") || key.startsWith("suite-")) throw new IllegalArgumentException("reserved grouped request key");
    var source=historySource(owner,sourceId);
    if(source.idempotencyKey().startsWith("commerce-")!=key.startsWith("commerce-"))
      throw new IllegalArgumentException("regeneration must retain its creation scope");
    return store.replayRegeneration(owner,sourceId,key);
  }
  public MediaTask regenerate(String owner,String sourceId,String key,TaskData.Presentation presentation) {
    var replay=replayRegeneration(owner,sourceId,key);
    if(replay.isPresent()) return replay.get();
    return store.regenerate(sourceId,candidate(owner,key,historySource(owner,sourceId).data().request(),presentation));
  }
  public Optional<MediaTask> findRequest(String owner,String key) { return store.findRequest(owner,key); }
  public Optional<MediaTask> replay(String owner,String key,TaskRequest request) {
    MediaReference.identifier(owner,"ownerId"); MediaReference.identifier(key,"Idempotency-Key");
    return store.findRequest(owner,key).map(task -> {
      if(!task.ownerId().equals(owner) || !task.idempotencyKey().equals(key) || !task.data().request().equals(request))
        throw new TaskConflictException("idempotency key already used with another request");
      return task;
    });
  }
  public MediaTask get(String owner,String id) {
    return store.find(id).filter(t -> t.ownerId().equals(owner)).orElseThrow(() -> new TaskNotFoundException("task not found"));
  }
  public List<MediaTask> list(String owner,int limit,String before,boolean excludeCanvas) {
    return list(owner,limit,before,excludeCanvas,false);
  }
  public List<MediaTask> list(String owner,int limit,String before,boolean excludeCanvas,boolean commerceOnly) {
    if(limit<1 || limit>100) throw new IllegalArgumentException("limit must be 1..100");
    if(before!=null) MediaReference.identifier(before,"beforeId");
    return store.list(owner,limit,before,excludeCanvas,commerceOnly);
  }
  public List<TaskResult> displayResults(String owner,String taskId) {
    get(owner,taskId);
    return store.results(taskId);
  }
  public List<TaskResult> results(String owner,String taskId) {
    MediaTask task=get(owner,taskId);
    if(task.status()!=TaskStatus.SUCCEEDED && task.status()!=TaskStatus.PARTIAL_SUCCESS) return List.of();
    return store.results(taskId).stream().filter(r -> r.fileId()!=null).toList();
  }
}
