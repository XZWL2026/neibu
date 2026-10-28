package com.xzwl.cloud.media.application.task;
import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.media.domain.task.*;
import java.util.List;

/** Local processing uses the existing durable task queue, without commercial generation dependencies. */
public final class DepthTasks {
  public interface Port {
    Source source(String owner,String file);
    void submit(MediaTask task);
    String poll(MediaTask task);
    String archive(MediaTask task);
  }
  public record Source(String url,String mimeType,long sizeBytes,Double durationSeconds,Integer width,Integer height,Double fps) {}
  private final MediaTaskService tasks;
  private final Port port;
  private final boolean enabled;
  public DepthTasks(MediaTaskService tasks,Port port,boolean enabled) { this.tasks=tasks;this.port=port;this.enabled=enabled; }
  public boolean enabled() { return enabled; }
  private void available() { if(!enabled) throw new DependencyUnavailableException("local depth processing is disabled"); }
  public MediaTask submit(String owner,String key,String file,String resolution) {
    available();
    MediaReference.identifier(owner,"ownerId"); MediaReference.identifier(key,"Idempotency-Key"); MediaReference.identifier(file,"sourceFileId");
    // Replay the original canonical request before obtaining a newly signed URL or changing metadata.
    var existing=tasks.findRequest(owner,key);
    if(existing.isPresent()) {
      var request=existing.get().data().request();
      if(request.operation()!=TaskOperation.DEPTH || !request.resolution().equals(resolution)
          || !request.references().getFirst().fileId().equals(file)) throw new TaskConflictException("idempotency key already used with another request");
      return existing.get();
    }
    Source source=port.source(owner,file);
    if(source.durationSeconds()==null || !Double.isFinite(source.durationSeconds()) || source.durationSeconds()<=0
        || source.durationSeconds()>Integer.MAX_VALUE || source.width()==null || source.height()==null || source.width()<=0 || source.height()<=0
        || source.fps()==null || !Double.isFinite(source.fps()) || source.fps()<=0 || source.mimeType()==null || !source.mimeType().startsWith("video/"))
      throw new DefinitiveRejectionException("INVALID_DEPTH_SOURCE");
    if(resolution==null || !List.of("480p","720p").contains(resolution) || source.durationSeconds()>30 && resolution.equals("720p"))
      throw new IllegalArgumentException("videos longer than 30 seconds require 480p");
    var request=new TaskRequest(MediaType.VIDEO,"depth-anything-v2-small","深度白模","source",resolution,1,
        (int)Math.ceil(source.durationSeconds()),List.of(new MediaReference(file,null,MediaReference.Kind.VIDEO,source.durationSeconds())),
        ReferenceMode.FIRST_LAST_FRAME,TaskOperation.DEPTH);
    return tasks.submit(owner,key,request,new TaskData.Presentation("深度白模（本地）",false));
  }
  public void start(MediaTask task) { available(); port.submit(task); }
  public String poll(MediaTask task) { available(); return port.poll(task); }
  public String archive(MediaTask task) { available(); return MediaReference.identifier(port.archive(task),"fileId"); }
}
