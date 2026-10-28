package com.xzwl.cloud.media.domain.task;

/** Output index is stable across polls, restarts and archive retries. */
public record TaskResult(String taskId, int index, String sourceUrl, String fileId, String failureCode) {
  public TaskResult(String taskId,int index,String sourceUrl,String fileId) { this(taskId,index,sourceUrl,fileId,null); }
  public TaskResult {
    MediaReference.identifier(taskId,"taskId");
    if (index < 0 || index > 15) throw new IllegalArgumentException("invalid output index");
    if (sourceUrl != null) MediaReference.https(sourceUrl);
    if (fileId != null) MediaReference.identifier(fileId,"fileId");
    if (failureCode!=null) MediaReference.identifier(failureCode,"failureCode");
    if (failureCode==null && sourceUrl == null && fileId == null) throw new IllegalArgumentException("missing output");
    if (failureCode!=null && (sourceUrl!=null || fileId!=null)) throw new IllegalArgumentException("failed output cannot contain media");
  }
  public TaskResult archived(String id) { return new TaskResult(taskId,index,null,id); }
  public TaskResult failed() { return new TaskResult(taskId,index,null,null,"ARCHIVE_REJECTED"); }
}
