package com.xzwl.cloud.media.interfaces.http;

import com.xzwl.cloud.media.application.task.PortraitReviews;
import com.xzwl.cloud.media.domain.task.MediaReference.Kind;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/media/portraits")
public final class PortraitController {
  private final PortraitReviews reviews;
  public PortraitController(PortraitReviews reviews) { this.reviews=reviews; }
  @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
  public PortraitResponse submit(Principal principal,@RequestHeader("Idempotency-Key") String key,
      @RequestBody SubmitRequest request,jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader("Cache-Control","no-store");
    return project(reviews.submit(owner(principal),key,request.fileId(),request.model(),request.kind()));
  }
  @GetMapping("/{portraitId}")
  public PortraitResponse get(Principal principal,@PathVariable("portraitId") String portraitId,jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader("Cache-Control","no-store");
    return project(reviews.get(owner(principal),portraitId));
  }
  @GetMapping
  public List<PortraitResponse> list(Principal principal,@RequestParam(value="limit",defaultValue="20") int limit,
      @RequestParam(value="beforeId",required=false) String beforeId,jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader("Cache-Control","no-store");
    return reviews.list(owner(principal),limit,beforeId).stream().map(PortraitController::project).toList();
  }
  private static String owner(Principal principal) {
    if(principal==null || principal.getName()==null || principal.getName().isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return principal.getName();
  }
  private static PortraitResponse project(PortraitReviews.Portrait portrait) {
    return new PortraitResponse(portrait.id(),portrait.fileId(),portrait.model(),portrait.status(),portrait.errorMessage(),portrait.kind(),portrait.durationSeconds());
  }
  public record SubmitRequest(String fileId,String model,Kind kind) {
    public SubmitRequest { kind=kind==null?Kind.IMAGE:kind; }
    public SubmitRequest(String fileId,String model) { this(fileId,model,Kind.IMAGE); }
  }
  public record PortraitResponse(String id,String fileId,String model,String status,String errorMessage,Kind kind,Double durationSeconds) {
    public PortraitResponse(String id,String fileId,String model,String status,String errorMessage) {
      this(id,fileId,model,status,errorMessage,Kind.IMAGE,null);
    }
  }
}
