package com.xzwl.cloud.media.interfaces.http;

import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.task.MediaTask;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/media/tasks")
public final class MediaAdminTaskController {
  private final MediaTaskStore tasks;
  private final MediaTaskController views;
  private final TaskFileAccess files;
  public MediaAdminTaskController(MediaTaskStore tasks,MediaTaskController views,TaskFileAccess files) {
    this.tasks=tasks;this.views=views;this.files=files;
  }
  @GetMapping("/{id}") public Snapshot get(@PathVariable("id") String id,HttpServletRequest request) {
    authorize(request);var task=required(id);
    var refs=task.data().request().references();var references=new ArrayList<Reference>();
    for(int i=0;i<refs.size();i++)references.add(new Reference(i,refs.get(i).fileId(),refs.get(i).kind()));
    var diagnostic=views.diagnostics(id);
    var generation=diagnostic==null?null:diagnostic.generation();
    var enhancement=diagnostic==null?null:diagnostic.enhancement();
    var finalOutputs=tasks.results(id).stream().map(r->new ProviderPort.DiagnosticOutput(r.index(),r.fileId(),r.failureCode())).toList();
    String name=task.data().route()==null?null:task.data().route().providerDisplayName();
    if(generation==null) generation=new ProviderPort.Stage(name,null,null,null,null,null,null,null,null,null,finalOutputs);
    else generation=stage(generation,name,enhancement==null?finalOutputs:generation.outputs(),task,enhancement==null);
    if(enhancement!=null) enhancement=stage(enhancement,null,finalOutputs,task,true);
    return new Snapshot(task.ownerId(),views.response(task.ownerId(),task,true),List.copyOf(references),
        task.data().refundPoints()==null?null:task.data().refundPoints().toPlainString(),generation,enhancement);
  }
  @GetMapping("/{id}/files/{fileId}/access-url") public ResponseEntity<TaskFileAccess.Access> access(
      @PathVariable("id") String id,@PathVariable("fileId") String fileId,HttpServletRequest request) {
    authorize(request);var task=required(id);
    boolean associated=task.data().request().references().stream().anyMatch(r->fileId.equals(r.fileId()))
        || tasks.results(id).stream().anyMatch(r->fileId.equals(r.fileId()));
    var diagnostic=views.diagnostics(id);
    if(!associated && diagnostic!=null && diagnostic.generation()!=null)
      associated=diagnostic.generation().outputs().stream().anyMatch(o->fileId.equals(o.fileId()));
    if(!associated && diagnostic!=null && diagnostic.enhancement()!=null)
      associated=diagnostic.enhancement().steps().stream().anyMatch(o->fileId.equals(o.fileId()));
    if(!associated)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(files.sign(task.ownerId(),fileId));
  }
  private static ProviderPort.Stage stage(ProviderPort.Stage s,String name,List<ProviderPort.DiagnosticOutput> outputs,MediaTask task,boolean current) {
    String status=s.status();
    if(current && task.status()==com.xzwl.cloud.media.domain.task.TaskStatus.TIMED_OUT && !"COMPLETE".equals(status)) status="TIMED_OUT";
    var steps=new ArrayList<>(s.steps());
    if(!steps.isEmpty()) {
      int last=steps.size()-1;var terminal=steps.get(last);
      var output=outputs.stream().filter(o->o.index()==0).findFirst().orElse(null);
      if(output!=null) steps.set(last,new ProviderPort.EnhancementStep(terminal.index(),terminal.definitionId(),terminal.width(),terminal.height(),
          terminal.status(),terminal.durationMs(),output.fileId(),output.failureCode()));
    }
    return new ProviderPort.Stage(s.provider()==null?name:s.provider(),s.taskId(),status,s.resolution(),s.durationMs(),s.videoTokens(),
        s.scheduleId(),s.definitionId(),s.failureStage(),s.failureCode(),outputs,steps);
  }
  private MediaTask required(String id) {return tasks.find(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));}
  private static void authorize(HttpServletRequest request) {
    if(request.getUserPrincipal()==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if(!request.isUserInRole("ADMIN") && !request.isUserInRole("NORMAL_ADMIN"))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }
  public record Reference(int index,String fileId,com.xzwl.cloud.media.domain.task.MediaReference.Kind kind) {}
  public record Snapshot(String ownerId,MediaTaskController.TaskResponse task,List<Reference> references,String expectedRefundPoints4,ProviderPort.Stage generation,ProviderPort.Stage enhancement) {}
}
