package com.xzwl.cloud.media.application.provider;

import java.util.List;
import java.util.Optional;

/** Read-only operational evidence; never changes task state or resubmits a provider request. */
public interface ProviderDiagnosticQuery {
  Page list(Filter filter);
  TaskPage tasks(Filter filter);
  Optional<Detail> find(String id);

  record Filter(String taskId,String phase,long from,long to,int offset,int limit) {
    public Filter {
      if(taskId!=null && !taskId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) throw new IllegalArgumentException("invalid task ID");
      if(phase!=null && !List.of("ARK_SENDING","ARK_RUNNING","MPS_SENDING","MPS_RUNNING").contains(phase)) throw new IllegalArgumentException("invalid phase");
      if(from<0 || to<=from || taskId==null && to-from>31L*86400000 || offset<0 || offset>10000 || limit<1 || limit>50)
        throw new IllegalArgumentException("invalid diagnostic range");
    }
  }
  record Entry(String id,String taskId,String phase,long createdAt,String event,String model,Integer httpStatus,
      Long elapsedMs,String requestId,String errorCode,String message) {}
  record Page(List<Entry> items,long total,int offset,int limit,long from,long to) {}
  record TaskGroup(String taskId,long recordCount,Entry latest) {}
  record TaskPage(List<TaskGroup> items,long total,int offset,int limit,long from,long to) {}
  record Detail(Entry entry,String detailJson) {}
}
