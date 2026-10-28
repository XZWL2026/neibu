package com.xzwl.cloud.media.interfaces.http;
import com.xzwl.cloud.media.application.task.EnhancementQuoteService;
import java.security.Principal;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/api/v1/media/tasks/{taskId}/outputs/{outputIndex}")
public final class EnhancementQuoteController {
  private final EnhancementQuoteService quotes;
  public EnhancementQuoteController(EnhancementQuoteService quotes) { this.quotes=quotes; }
  @GetMapping("/enhancement-options")
  public ResponseEntity<EnhancementQuoteService.Options> options(Principal principal,@PathVariable("taskId") String taskId,@PathVariable("outputIndex") int index) {
    if(principal==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(quotes.options(principal.getName(),taskId,index));
  }
}
