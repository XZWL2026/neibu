package com.xzwl.cloud.promotion.interfaces.http;

import com.xzwl.cloud.promotion.application.production.AdminAgentDirectory;
import com.xzwl.cloud.promotion.application.production.AdminAgentDirectory.*;
import com.xzwl.cloud.promotion.application.production.PromotionStore.Page;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public final class AdminAgentDirectoryController {
  private final AdminAgentDirectory directory;
  public AdminAgentDirectoryController(AdminAgentDirectory directory) { this.directory=directory; }
  @GetMapping("/api/v1/admin/promotions/agent-directory")
  public DirectoryPage search(HttpServletRequest request,
      @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size,
      @RequestParam(name="search",defaultValue="") String search,@RequestParam(name="source",required=false) String source,
      @RequestParam(name="enabled",required=false) Boolean enabled,@RequestParam(name="from",required=false) Instant from,
      @RequestParam(name="to",required=false) Instant to) {
    boolean full=reader(request);return directory.search(new Query(page,size,search,source,enabled,from,to),full);
  }
  @GetMapping("/api/v1/admin/promotions/agent-directory/{userId}")
  public Detail detail(HttpServletRequest request,@PathVariable("userId") String userId) {
    boolean full=reader(request);return directory.detail(userId,full);
  }
  @GetMapping("/api/v1/admin/promotions/agent-directory/{userId}/downlines")
  public Page<Downline> downlines(HttpServletRequest request,@PathVariable("userId") String userId,
      @RequestParam(name="search",defaultValue="") String search,@RequestParam(name="accountType",required=false) String accountType,
      @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size) {
    boolean full=reader(request);return directory.downlines(userId,search,accountType,page,size,full);
  }
  @GetMapping("/api/v1/admin/promotions/agent-directory/{userId}/operations")
  public Page<Operation> operations(HttpServletRequest request,@PathVariable("userId") String userId,
      @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size) {
    reader(request);return directory.operations(userId,page,size);
  }
  private static boolean reader(HttpServletRequest request) {
    if(request.getUserPrincipal()==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if(!request.isUserInRole("ADMIN")&&!request.isUserInRole("NORMAL_ADMIN"))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return request.isUserInRole("ADMIN");
  }
}
