package com.xzwl.cloud.media.interfaces.http;

import com.xzwl.cloud.media.application.suite.ProductSuites;
import com.xzwl.cloud.media.application.suite.ProductSuites.Module;
import com.xzwl.cloud.media.domain.task.TaskStatus;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/media/product-suites")
public class ProductSuiteController {
  private final ProductSuites suites;
  private final MediaTaskController tasks;
  public ProductSuiteController(ProductSuites suites,MediaTaskController tasks) { this.suites=suites;this.tasks=tasks; }
  @ModelAttribute void privateResponse(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
  @GetMapping("/templates") public Catalog templates(Principal principal) {
    owner(principal);return new Catalog(suites.templates(),suites.copyAvailable());
  }
  @PostMapping("/copy") public ProductSuites.Copy copy(Principal principal,@RequestBody ProductSuites.CopyRequest request) {
    return suites.copy(owner(principal),request);
  }
  @PostMapping("/video-breakdown") public ProductSuites.VideoBreakdown videoBreakdown(Principal principal,@RequestBody ProductSuites.VideoBreakdownRequest request) {
    return suites.videoBreakdown(owner(principal),request);
  }
  @PostMapping("/plan") public ProductSuites.Plan plan(Principal principal,@RequestBody ProductSuites.CopyRequest request) {
    return suites.plan(owner(principal),request);
  }
  @PostMapping("/quotes") public ProductSuites.Quote quote(Principal principal,@RequestBody ProductSuites.Request request) {
    return suites.quote(owner(principal),request);
  }
  @PostMapping public SuiteResponse submit(Principal principal,@RequestHeader("Idempotency-Key") String key,@RequestBody ProductSuites.Request request) {
    return response(principal,suites.submit(owner(principal),key,request));
  }
  @GetMapping public List<SuiteResponse> list(Principal principal,@RequestParam(value="limit",defaultValue="20") int limit,
      @RequestParam(value="before",required=false) String before) {
    return suites.list(owner(principal),limit,before).stream().map(suite->response(principal,suite)).toList();
  }
  @GetMapping("/{suiteId}") public SuiteResponse get(Principal principal,@PathVariable("suiteId") String id) {
    return response(principal,suites.get(owner(principal),id));
  }
  @PostMapping("/{suiteId}/modules/{module}/regenerations") public SuiteResponse regenerate(Principal principal,
      @PathVariable("suiteId") String id,@PathVariable("module") Module module,
      @RequestHeader("Idempotency-Key") String key,@RequestBody RegenerationRequest request) {
    return response(principal,suites.regenerate(owner(principal),id,module,request.sourceTaskId(),key));
  }
  private SuiteResponse response(Principal principal,ProductSuites.Suite suite) {
    var template=ProductSuites.template(suite.request());
    var items=suite.items().stream().map(item->{
      var latest=tasks.get(principal,item.taskIds().getLast());
      MediaTaskController.TaskResponse previous=null;
      for(int i=item.taskIds().size()-2;i>=0;i--) {
        var candidate=tasks.get(principal,item.taskIds().get(i));
        if(!candidate.results().isEmpty()) { previous=candidate;break; }
      }
      return new ItemResponse(item.module(),template.module(item.module()).name(),latest,previous);
    }).toList();
    boolean terminal=items.stream().allMatch(i->TaskStatus.valueOf(i.task().status()).terminal());
    long succeeded=items.stream().filter(i->i.task().status().equals("SUCCEEDED")).count();
    boolean partial=items.stream().anyMatch(i->i.task().status().equals("PARTIAL_SUCCESS"));
    String status=!terminal?"RUNNING":succeeded==items.size()?"SUCCEEDED":succeeded>0 || partial?"PARTIAL_SUCCESS":"FAILED";
    return new SuiteResponse(suite.id(),suite.createdAt(),suite.request(),status,items);
  }
  private static String owner(Principal principal) {
    if(principal==null || principal.getName()==null || principal.getName().isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return principal.getName();
  }
  public record Catalog(List<ProductSuites.Template> templates,boolean copyAvailable) {}
  public record RegenerationRequest(String sourceTaskId) {}
  public record ItemResponse(Module module,String name,MediaTaskController.TaskResponse task,MediaTaskController.TaskResponse previousResult) {}
  public record SuiteResponse(String id,Instant createdAt,ProductSuites.Request request,String status,List<ItemResponse> items) {}
}
