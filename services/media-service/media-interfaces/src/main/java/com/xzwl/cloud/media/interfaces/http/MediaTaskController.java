package com.xzwl.cloud.media.interfaces.http;

import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.application.catalog.GenerationCatalogService;
import com.xzwl.cloud.media.domain.task.*;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/media/tasks")
public final class MediaTaskController {
  private final MediaTaskService service;
  private final MediaTaskWorker worker;
  private final GenerationCatalogService catalog;
  private final ProviderPort provider;
  private final com.fasterxml.jackson.databind.ObjectMapper json;
  private final EnhancementTasks enhancements;
  public MediaTaskController(MediaTaskService service,MediaTaskWorker worker,GenerationCatalogService catalog,ProviderPort provider,com.fasterxml.jackson.databind.ObjectMapper json) {
    this(service,worker,catalog,provider,json,null);
  }
  @org.springframework.beans.factory.annotation.Autowired
  public MediaTaskController(MediaTaskService service,MediaTaskWorker worker,GenerationCatalogService catalog,ProviderPort provider,com.fasterxml.jackson.databind.ObjectMapper json,EnhancementTasks enhancements) {
    this.enhancements=enhancements;this.service=service; this.worker=worker; this.catalog=catalog; this.provider=provider; this.json=json;
  }

  @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.ACCEPTED)
  public TaskResponse submit(Principal principal,@RequestHeader("Idempotency-Key") String key,@RequestBody TaskRequest request) {
    String owner=owner(principal);
    if(request.operation()!=TaskOperation.GENERATION) throw new IllegalArgumentException("use depth-tasks for processing");
    var replay=service.replay(owner,key,request);
    if(replay.isPresent()) return response(owner,replay.get());
    MediaTask task=service.submit(owner,key,request,presentation(owner,request));
    return admit(owner,task);
  }
  @PostMapping("/{taskId}/regenerations")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public TaskResponse regenerate(Principal principal,@PathVariable("taskId") String id,@RequestHeader("Idempotency-Key") String key) {
    String owner=owner(principal);
    var replay=service.replayRegeneration(owner,id,key);
    if(replay.isPresent()) return response(owner,replay.get());
    TaskRequest request=service.historySource(owner,id).data().request();
    return admit(owner,service.regenerate(owner,id,key,presentation(owner,request)));
  }
  public record EnhancementSubmission(String quoteId) {}
  @PostMapping("/{taskId}/outputs/{outputIndex}/enhancements")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public TaskResponse submitEnhancement(Principal principal,@PathVariable("taskId") String id,@PathVariable("outputIndex") int index,@RequestHeader("Idempotency-Key") String key,@RequestBody EnhancementSubmission request) {
    String owner=owner(principal);var task=enhancements.submit(owner,id,index,key,request.quoteId());
    if(task.status()==TaskStatus.CHARGE_PENDING)worker.process(task.id());
    return response(owner,service.get(owner,task.id()));
  }
  @DeleteMapping("/{taskId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteHistory(Principal principal,@PathVariable("taskId") String id) {
    service.deleteHistory(owner(principal),id);
  }
  private TaskData.Presentation presentation(String owner,TaskRequest request) {
    catalog.validate(owner,request);
    var option=catalog.options(owner).stream().filter(o -> o.type()==request.type() && o.model().equals(request.model())).findFirst().orElseThrow();
    return new TaskData.Presentation(option.displayName(),request.referenceMode()!=ReferenceMode.OMNI && !request.references().isEmpty() && option.referenceFollowsFirstFrame());
  }
  private TaskResponse admit(String owner,MediaTask task) {
    // Prepare the route and charge during admission; persisted stages survive a disconnected client.
    if(task.status()==TaskStatus.ROUTING) worker.process(task.id());
    if(service.get(owner,task.id()).status()==TaskStatus.CHARGE_PENDING) worker.process(task.id());
    return response(owner,service.get(owner,task.id()));
  }
  @GetMapping("/{taskId}")
  public TaskResponse get(Principal principal,@PathVariable("taskId") String id) {
    String owner=owner(principal); return response(owner,service.get(owner,id));
  }
  @GetMapping
  public List<TaskResponse> list(Principal principal,@RequestParam(value="limit",defaultValue="20") int limit,
      @RequestParam(value="beforeId",required=false) String before,
      @RequestParam(value="excludeCanvas",required=false) Boolean excludeCanvas,
      @RequestParam(value="commerceOnly",required=false) Boolean commerceOnly) {
    String owner=owner(principal); return service.list(owner,limit,before,Boolean.TRUE.equals(excludeCanvas),Boolean.TRUE.equals(commerceOnly)).stream().map(t -> response(owner,t)).toList();
  }
  @GetMapping("/{taskId}/results")
  public List<OutputResponse> results(Principal principal,@PathVariable("taskId") String id) {
    return service.results(owner(principal),id).stream().map(r -> new OutputResponse(r.index(),r.fileId())).toList();
  }
  ProviderPort.Diagnostics diagnostics(String id) { return provider.diagnostics(id); }
  TaskResponse response(String owner,MediaTask task) { return response(owner,task,false); }
  TaskResponse response(String owner,MediaTask task,boolean administrator) {
    TaskRequest request=task.data().request();
    var stored=service.displayResults(owner,task.id());
    var detail=provider.failure(task.id());
    boolean failed=task.status()==TaskStatus.REFUND_PENDING || task.status()==TaskStatus.FAILED
        || task.status()==TaskStatus.TIMED_OUT || task.status()==TaskStatus.PARTIAL_SUCCESS;
    String message=request.operation()==TaskOperation.ENHANCEMENT && !administrator
        ? task.data().charge()==null ? "增强未开始，请重新获取报价；原生视频保留" : "增强失败，请查看本次退款状态；原生视频保留"
        : failureMessage(administrator,task.data().failureCode(),detail);
    var failures=new java.util.ArrayList<FailureResponse>();
    if(failed) for(int index=0;index<request.count();index++) {
      final int outputIndex=index;
      var output=stored.stream().filter(r -> r.index()==outputIndex).findFirst().orElse(null);
      if(output==null || output.fileId()==null) failures.add(new FailureResponse(index,failureMessage(administrator,
          output!=null && output.failureCode()!=null ? output.failureCode() : task.data().failureCode(),detail)));
    }
    String refund=task.status()==TaskStatus.REFUND_PENDING ? "PENDING"
        : task.status().terminal() && task.data().refundPoints()!=null && task.data().refundPoints().signum()>0 ? "COMPLETED" : "NOT_REQUIRED";
    var presentation=task.data().presentation();
    return new TaskResponse(task.id(),task.status().name(),request.type().name(),request.model(),request.aspectRatio(),request.resolution(),
        request.count(),request.durationSeconds(),task.createdAt(),task.deadlineAt(),
        stored.stream().filter(r -> r.fileId()!=null).map(r -> new OutputResponse(r.index(),r.fileId())).toList(),
        request.prompt(),administrator ? List.of() : request.references(),modelName(task.data()),
        request.referenceMode()!=ReferenceMode.OMNI && (presentation!=null ? presentation.referenceFollowsFirstFrame() : !request.references().isEmpty()
            && task.data().route()!=null && "doubao-seedance-2-5-260628".equals(task.data().route().providerModel())),
        failed ? message : null,failures,refund,request.referenceMode(),request.operation(), enhancementViews(owner,task));
  }

  private List<EnhancementResponse> enhancementViews(String owner,MediaTask parent) {
    if(enhancements==null || parent.data().request().operation()!=TaskOperation.GENERATION)return List.of();
    return enhancements.list(owner,parent.id()).stream().map(task->{
      var quote=enhancements.quote(task.id());var view=response(owner,task);var source=quote.source();
      return new EnhancementResponse(task.id(),view.status(),source.outputIndex(),source.fileId(),quote.targetResolution(),source.billableSeconds(),quote.cost().amount().setScale(4).toPlainString(),quote.cost().points().setScale(4).toPlainString(),view.results(),view.refundStatus(),view.failureMessage());
    }).toList();
  }
  public record EnhancementResponse(String id,String status,int sourceOutputIndex,String sourceFileId,String targetResolution,int billableSeconds,String amount,String points,List<OutputResponse> results,String refundStatus,String failureMessage) {}
  private static String failureMessage(boolean administrator,String code,ProviderPort.FailureDetail detail) {
    if(!administrator) return MediaFailureMessages.message(code,detail);
    return detail==null ? code : detail.stage()+" · "+detail.code()+(detail.status()==null?"":" · "+detail.status());
  }
  private String modelName(TaskData data) {
    if(data.presentation()!=null) return data.presentation().modelName();
    if(data.route()!=null) try {
      String name=json.readTree(data.route().modelConfigurationJson()).path("displayName").asText("");
      if(!name.isBlank() && name.length()<=128) return name;
    } catch(com.fasterxml.jackson.core.JsonProcessingException invalidSnapshot) { /* Keep the persisted model ID as the fallback. */ }
    return data.request().model();
  }
  private static String owner(Principal principal) {
    if(principal==null || principal.getName()==null || principal.getName().isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return principal.getName();
  }
  public record OutputResponse(int index,String fileId) {}
  public record FailureResponse(int index,String message) {}
  public record TaskResponse(String id,String status,String type,String model,String aspectRatio,String resolution,int count,
      int durationSeconds,Instant createdAt,Instant deadlineAt,List<OutputResponse> results, String prompt,List<MediaReference> references,
      String modelName,boolean referenceFollowsFirstFrame,String failureMessage,List<FailureResponse> failures,String refundStatus,ReferenceMode referenceMode,TaskOperation operation,List<EnhancementResponse> enhancements) {}
}
