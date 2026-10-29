package com.xzwl.cloud.media.infrastructure.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.task.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class MyBatisMediaTaskStore implements MediaTaskStore {
  private final TaskMapper tasks;
  private final StateMapper states;
  private final ResultMapper outputs;
  private final ObjectMapper json;
  private final MediaSettlementOutbox settlements;
  private final TransactionTemplate transaction;
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;
  public MyBatisMediaTaskStore(TaskMapper tasks,StateMapper states,ResultMapper outputs,
      ObjectMapper json,PlatformTransactionManager transactions,org.springframework.jdbc.core.JdbcTemplate jdbc) {
    this.jdbc=jdbc;this.tasks=tasks; this.states=states; this.outputs=outputs; this.json=json;
    transaction=new TransactionTemplate(transactions);
    settlements=new MediaSettlementOutbox(jdbc,json);
  }

  @Override public MediaTask create(MediaTask candidate) {
    return transaction.execute(tx -> create(candidate,null));
  }
  private MediaTask create(MediaTask candidate,String sourceId) {
    lockRequest(candidate.ownerId(),candidate.idempotencyKey());
    if(jdbc.queryForObject("SELECT COUNT(*) FROM media_enhancement_request WHERE owner_id=? AND request_key=?",Integer.class,candidate.ownerId(),candidate.idempotencyKey())>0)
      throw new TaskConflictException("idempotency key belongs to an enhancement");
    TaskRow row=row(candidate); row.regenerationSourceId=sourceId;
    tasks.insertOnce(row);
    TaskRow stored=request(candidate.ownerId(),candidate.idempotencyKey());
    if(stored==null || !Objects.equals(stored.regenerationSourceId,sourceId))
      throw new TaskConflictException("idempotency key already used by another operation");
    MediaTask actual=read(stored);
    if(!actual.data().request().equals(candidate.data().request()))
      throw new TaskConflictException("idempotency key already used with another request");
    if(actual.id().equals(candidate.id())) states.insertOnce(state(null,candidate,candidate.createdAt()));
    return actual;
  }
  private TaskRow request(String owner,String key) {
    return tasks.selectOne(new QueryWrapper<TaskRow>().eq("owner_id",owner).eq("idempotency_key",key));
  }
  @Override public Optional<MediaTask> findRequest(String owner,String key) {
    if(jdbc.queryForObject("SELECT COUNT(*) FROM media_enhancement_request WHERE owner_id=? AND request_key=?",Integer.class,owner,key)>0)throw new TaskConflictException("idempotency key belongs to an enhancement");
    TaskRow row=request(owner,key);
    if(row!=null && row.regenerationSourceId!=null) throw new TaskConflictException("idempotency key belongs to a regeneration");
    return Optional.ofNullable(row).map(this::read);
  }
  @Override public Optional<MediaTask> replayRegeneration(String owner,String sourceId,String key) {
    TaskRow row=request(owner,key);
    if(row!=null && !sourceId.equals(row.regenerationSourceId))
      throw new TaskConflictException("idempotency key already used by another operation");
    return Optional.ofNullable(row).map(this::read);
  }
  @Override public void deleteHistory(String owner,String id) {
    transaction.executeWithoutResult(tx -> {
      historySource(tasks.lockOwned(owner,id));
      preventHidingActiveEnhancements(owner,id);
      tasks.deleteHistory(owner,id);
    });
  }
  @Override public MediaTask regenerate(String sourceId,MediaTask candidate) {
    return transaction.execute(tx -> {
      TaskRow source=tasks.lockOwned(candidate.ownerId(),sourceId);
      MediaTask original=historySource(source);
      var replay=replayRegeneration(candidate.ownerId(),sourceId,candidate.idempotencyKey());
      if(replay.isPresent()) return replay.get();
      if(Boolean.TRUE.equals(source.historyDeleted)) throw new TaskConflictException("task history already removed");
      if(!original.data().request().equals(candidate.data().request())) throw new TaskConflictException("regeneration request differs from source");
      preventHidingActiveEnhancements(candidate.ownerId(),sourceId);
      MediaTask replacement=create(candidate,sourceId);
      if(candidate.data().request().type()!=com.xzwl.cloud.media.domain.generation.MediaType.VIDEO)
        tasks.deleteHistory(candidate.ownerId(),sourceId);
      return replacement;
    });
  }
  private void lockRequest(String owner,String key) {
    // Shares the identity namespace with generation, regeneration and enhancement aliases.
    // Source locks precede this lock for both regeneration and enhancement admission.
    jdbc.update("INSERT INTO media_task_request_lock(owner_id,request_key) VALUES(?,?) ON DUPLICATE KEY UPDATE request_key=request_key",owner,key);
    jdbc.queryForObject("SELECT request_key FROM media_task_request_lock WHERE owner_id=? AND request_key=? FOR UPDATE",String.class,owner,key);
  }
  private void preventHidingActiveEnhancements(String owner,String source) {
    if(enhancements(owner,source).stream().anyMatch(t->!t.status().terminal()))throw new TaskConflictException("enhancement is processing; wait before hiding this video");
  }
  @Override public Optional<MediaTask> replayEnhancement(String owner,String source,int index,String key,String quoteId) {
    var matches=jdbc.query("SELECT source_task_id,output_index,quote_id,task_id FROM media_enhancement_request WHERE owner_id=? AND request_key=?",(row,n)->{
      if(!source.equals(row.getString(1)) || index!=row.getInt(2) || !quoteId.equals(row.getString(3)))throw new TaskConflictException("idempotency key already used with another enhancement request");
      return find(row.getString(4)).orElseThrow(()->new IllegalStateException("missing enhancement attempt"));
    },owner,key);
    if(matches.isEmpty() && request(owner,key)!=null)throw new TaskConflictException("idempotency key already used by another operation");
    return matches.stream().findFirst();
  }
  @Override public MediaTask createEnhancement(String owner,String sourceId,int index,String key,String quoteId,String target,java.util.function.Supplier<EnhancementCreation> creation) {
    return transaction.execute(tx->{
      TaskRow source=tasks.lockOwned(owner,sourceId);
      if(source==null)throw new TaskNotFoundException("native task not found");
      lockRequest(owner,key);
      var replay=replayEnhancement(owner,sourceId,index,key,quoteId);if(replay.isPresent())return replay.get();
      if(Boolean.TRUE.equals(source.historyDeleted))throw new TaskConflictException("task history already removed");
      MediaTask original=read(source);
      if(original.data().request().operation()!=TaskOperation.GENERATION || original.data().request().type()!=com.xzwl.cloud.media.domain.generation.MediaType.VIDEO
        || !(original.status()==TaskStatus.SUCCEEDED || original.status()==TaskStatus.PARTIAL_SUCCESS))throw new TaskConflictException("invalid native source");
      MediaTask accepted=enhancements(owner,sourceId).stream().filter(t->{var q=enhancementQuote(t.id()).orElseThrow();return q.source().outputIndex()==index && target.equals(q.targetResolution());})
          .filter(t->!t.status().terminal() || t.status()==TaskStatus.SUCCEEDED).findFirst().orElse(null);
      if(accepted==null) {
        var candidate=creation.get();var quote=candidate.quote();
        if(results(sourceId).stream().noneMatch(r->r.index()==index && quote.source().fileId().equals(r.fileId())))throw new TaskConflictException("native file changed");
        accepted=create(candidate.task(),null);
        try {jdbc.update("INSERT INTO media_enhancement_attempt(task_id,owner_id,source_task_id,output_index,source_file_id,target_resolution,quote_json) VALUES(?,?,?,?,?,?,?)",accepted.id(),owner,sourceId,index,quote.source().fileId(),target,json.writeValueAsString(quote));}
        catch(JsonProcessingException failure){throw new IllegalStateException("cannot encode accepted enhancement",failure);}
      }
      try {jdbc.update("INSERT INTO media_enhancement_request(owner_id,request_key,source_task_id,output_index,quote_id,task_id) VALUES(?,?,?,?,?,?)",owner,key,sourceId,index,quoteId,accepted.id());}
      catch(org.springframework.dao.DuplicateKeyException conflict){throw new TaskConflictException("idempotency key belongs to another enhancement");}
      return accepted;
    });
  }
  @Override public Optional<EnhancementQuoteStore.Quote> enhancementQuote(String id) {
    return jdbc.query("SELECT quote_json FROM media_enhancement_attempt WHERE task_id=?",(row,n)->{
      try{return json.readValue(row.getString(1),EnhancementQuoteStore.Quote.class);}catch(JsonProcessingException failure){throw new IllegalStateException("invalid accepted enhancement",failure);}
    },id).stream().findFirst();
  }
  @Override public List<MediaTask> enhancements(String owner,String source) {
    return jdbc.query("SELECT t.* FROM media_task t JOIN media_enhancement_attempt e ON t.id=e.task_id WHERE e.owner_id=? AND e.source_task_id=? ORDER BY t.created_at,t.id",(row,n)->{
      try{return json.readValue(row.getString("task_json"),MediaTask.class);}catch(JsonProcessingException failure){throw new IllegalStateException("invalid enhancement task",failure);}
    },owner,source);
  }
  private MediaTask historySource(TaskRow row) {
    if(row==null) throw new TaskNotFoundException("task not found");
    MediaTask task=read(row);
    if(!task.status().terminal() || task.data().request().operation()!=TaskOperation.GENERATION
        || task.idempotencyKey().startsWith("canvas-") || task.idempotencyKey().startsWith("suite-")) throw new TaskConflictException("task is not an independent terminal generation");
    return task;
  }
  @Override public boolean isHistoryVisible(String owner,String id) {
    var row=tasks.selectById(id);return row!=null && owner.equals(row.ownerId) && !Boolean.TRUE.equals(row.historyDeleted);
  }
  @Override public Optional<MediaTask> find(String id) { return Optional.ofNullable(tasks.selectById(id)).map(this::read); }
  @Override public List<MediaTask> list(String owner,int limit,String before,boolean excludeCanvas,boolean commerceOnly) {
    checkLimit(limit);
    // A deleted history entry remains a valid cursor, but another owner's task does not.
    TaskRow cursor=before==null ? null : tasks.selectOne(new QueryWrapper<TaskRow>().eq("owner_id",owner).eq("id",before));
    if(before!=null && cursor==null) throw new TaskNotFoundException("task not found");
    var query=new QueryWrapper<TaskRow>().eq("owner_id",owner).eq("history_deleted",false).ne("operation","ENHANCEMENT")
        // Apply creation scope before cursor pagination, including historical grouped tasks.
        .notLikeRight(excludeCanvas,"idempotency_key","canvas-")
        .notLikeRight(excludeCanvas,"idempotency_key","suite-")
        .notLikeRight(excludeCanvas,"idempotency_key","model-")
        .notLikeRight(excludeCanvas && !commerceOnly,"idempotency_key","commerce-")
        .likeRight(commerceOnly,"idempotency_key","commerce-");
    // Task IDs are random UUIDs; only use them to break creation-time ties.
    if(cursor!=null) query.and(q -> q.lt("created_at",cursor.createdAt)
        .or(sameTime -> sameTime.eq("created_at",cursor.createdAt).lt("id",cursor.id)));
    return tasks.selectList(query.orderByDesc("created_at","id").last("LIMIT "+limit)).stream().map(this::read).toList();
  }
  @Override public List<String> due(Instant now,int limit) {
    checkLimit(limit);
    return tasks.selectList(new QueryWrapper<TaskRow>().select("id")
        .notIn("status","SUCCEEDED","PARTIAL_SUCCESS","FAILED","TIMED_OUT")
        .le("next_run_at",now.toEpochMilli())
        .and(q -> q.isNull("lease_until").or().le("lease_until",now.toEpochMilli()))
        .orderByAsc("next_run_at","id").last("LIMIT "+limit)).stream().map(r -> r.id).toList();
  }
  @Override public Optional<Lease> claim(String id,String token,Instant now,Instant until) {
    if(!until.isAfter(now)) throw new IllegalArgumentException("lease must expire in the future");
    return transaction.execute(tx -> tasks.claim(id,token,now.toEpochMilli(),until.toEpochMilli())==1
        ? Optional.of(new Lease(read(tasks.selectById(id)),token)):Optional.empty());
  }
  @Override public void save(Lease lease,MediaTask next,List<TaskResult> results,Instant now) {
    transaction.executeWithoutResult(tx -> {
      MediaTask old=lease.task();
      if(!old.id().equals(next.id()) || next.version()!=old.version()+1
          || !old.canTransitionTo(next.status())) throw new IllegalArgumentException("invalid task transition");
      if(tasks.advance(row(next),old.version(),old.status().name(),lease.token(),now.toEpochMilli())!=1)
        throw new LostLeaseException("task lease expired or was superseded");
      for(TaskResult result:results) {
        if(!result.taskId().equals(next.id())) throw new IllegalArgumentException("result belongs to another task");
        ResultRow row=new ResultRow(); row.id=result.taskId()+":"+result.index();
        row.taskId=result.taskId(); row.outputIndex=result.index(); row.sourceUrl=result.sourceUrl(); row.fileId=result.fileId();
        row.failureCode=result.failureCode();
        ResultRow previous=outputs.selectById(row.id);
        if(previous!=null && previous.fileId!=null && !previous.fileId.equals(row.fileId))
          throw new IllegalArgumentException("archived result cannot be replaced");
        if(previous!=null && previous.failureCode!=null && !previous.failureCode.equals(row.failureCode))
          throw new IllegalArgumentException("rejected result cannot be replaced");
        if(previous!=null && row.fileId==null && row.failureCode==null && !Objects.equals(previous.sourceUrl,row.sourceUrl))
          throw new IllegalArgumentException("provider result cannot be replaced");
        outputs.insertOnce(row);
        if(row.fileId!=null) outputs.archive(row.id,row.fileId);
        if(row.failureCode!=null) outputs.fail(row.id,row.failureCode);
      }
      states.insertOnce(state(old,next,now));
      settlements.record(next,now);
    });
  }
  @Override public List<TaskResult> results(String id) {
    return outputs.selectList(new QueryWrapper<ResultRow>().eq("task_id",id).orderByAsc("output_index"))
        .stream().map(r -> new TaskResult(r.taskId,r.outputIndex,r.sourceUrl,r.fileId,r.failureCode)).toList();
  }
  private TaskRow row(MediaTask task) {
    TaskRow row=new TaskRow(); row.id=task.id(); row.ownerId=task.ownerId(); row.idempotencyKey=task.idempotencyKey();
    row.operation=task.data().request().operation().name();row.status=task.status().name(); row.createdAt=task.createdAt().toEpochMilli();
    row.nextRunAt=task.nextRunAt().toEpochMilli(); row.version=task.version();
    try { row.taskJson=json.writeValueAsString(task); }
    catch(JsonProcessingException failed) { throw new IllegalStateException("cannot encode media task",failed); }
    return row;
  }
  private MediaTask read(TaskRow row) {
    try { return json.readValue(row.taskJson,MediaTask.class); }
    catch(JsonProcessingException failed) { throw new IllegalStateException("cannot decode media task",failed); }
  }
  private static StateRow state(MediaTask previous,MediaTask next,Instant now) {
    StateRow row=new StateRow(); row.id=next.id()+":"+next.version(); row.taskId=next.id(); row.version=next.version();
    row.fromStatus=previous==null?null:previous.status().name(); row.toStatus=next.status().name(); row.occurredAt=now.toEpochMilli();
    return row;
  }
  private static void checkLimit(int limit) {
    if(limit<1 || limit>100) throw new IllegalArgumentException("limit must be 1..100");
  }
}
