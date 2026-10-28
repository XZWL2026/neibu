package com.xzwl.cloud.media.interfaces.http;
import com.xzwl.cloud.media.application.task.DepthTasks;
import java.security.Principal;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/api/v1/media")
public final class DepthController {
  private final DepthTasks depths;
  private final MediaTaskController tasks;
  public DepthController(DepthTasks depths,MediaTaskController tasks) { this.depths=depths;this.tasks=tasks; }
  @GetMapping("/depth-options") public Options options() { return new Options(depths.enabled(),List.of("480p","720p"),30); }
  @PostMapping(value="/depth-tasks",consumes=MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.ACCEPTED)
  public MediaTaskController.TaskResponse submit(Principal principal,@RequestHeader("Idempotency-Key") String key,@RequestBody Submission input) {
    if(principal==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    var task=depths.submit(principal.getName(),key,input.sourceFileId(),input.resolution());
    return tasks.response(principal.getName(),task);
  }
  public record Submission(String sourceFileId,String resolution) {}
  public record Options(boolean enabled,List<String> resolutions,int highResolutionMaxDurationSeconds) {}
}
