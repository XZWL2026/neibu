package com.xzwl.cloud.media.interfaces.http;

import com.xzwl.cloud.media.application.catalog.GenerationCatalogService;
import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.media.domain.task.TaskRequest;
import com.xzwl.cloud.media.domain.task.MediaReference;
import com.xzwl.cloud.media.domain.task.ReferenceMode;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/media")
public final class MediaGenerationController {
  private final GenerationCatalogService catalog;
  public MediaGenerationController(GenerationCatalogService catalog) { this.catalog=catalog; }
  @GetMapping("/generation-options")
  public Options options(Principal principal,jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader("Cache-Control","no-store");
    return new Options(catalog.options(owner(principal)));
  }
  @PostMapping("/quotes")
  public Quote quote(Principal principal,@RequestBody QuoteRequest request) {
    String owner=owner(principal);
    try {
      if(request.type()==null) throw new IllegalArgumentException("type required");
      var selection=new TaskRequest(request.type(),request.model(),"quote",request.aspectRatio(),request.resolution(),
          request.count(),request.durationSeconds(),request.references(),request.referenceMode());
      return new Quote(catalog.quote(owner,selection).setScale(4).toPlainString());
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"unsupported generation selection");
    }
  }
  private static String owner(Principal principal) {
    if(principal==null || principal.getName()==null || principal.getName().isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return principal.getName();
  }
  public record Options(List<GenerationCatalogService.Option> models) {}
  public record Quote(String points) {}
  public record QuoteRequest(MediaType type,String model,String aspectRatio,String resolution,int count,int durationSeconds,List<MediaReference> references,ReferenceMode referenceMode) {}
}
