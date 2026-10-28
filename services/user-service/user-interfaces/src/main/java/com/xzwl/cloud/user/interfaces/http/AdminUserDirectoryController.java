package com.xzwl.cloud.user.interfaces.http;

import com.xzwl.cloud.user.application.identity.AdminUserDirectory;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public final class AdminUserDirectoryController {
  private final AdminUserDirectory directory;
  public AdminUserDirectoryController(AdminUserDirectory directory){this.directory=directory;}
  @GetMapping("/api/v1/admin/users/directory")
  public AdminUserDirectory.Page directory(@RequestParam(name="offset",defaultValue="0") int offset,
      @RequestParam(name="limit",defaultValue="20") int limit,@RequestParam(name="search",defaultValue="") String search,
      @RequestParam(name="accountType",required=false) String accountType,@RequestParam(name="source",required=false) String source,
      @RequestParam(name="enabled",required=false) Boolean enabled,@RequestParam(name="keyStatus",required=false) String keyStatus,
      @RequestParam(name="from",required=false) Instant from,@RequestParam(name="to",required=false) Instant to,
      Authentication authentication,jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader("Cache-Control","no-store");
    return directory.search(new AdminUserDirectory.Query(offset,limit,search,accountType,source,enabled,keyStatus,from,to==null?Instant.now():to),primary(authentication));
  }
  @GetMapping("/api/v1/admin/api-users/directory")
  public AdminUserDirectory.ApiPage apiDirectory(@RequestParam(name="offset",defaultValue="0") int offset,
      @RequestParam(name="limit",defaultValue="20") int limit,@RequestParam(name="search",defaultValue="") String search,
      @RequestParam(name="source",required=false) String source,@RequestParam(name="enabled",required=false) Boolean enabled,
      @RequestParam(name="keyStatus",required=false) String keyStatus,@RequestParam(name="from",required=false) Instant from,
      @RequestParam(name="to",required=false) Instant to,Authentication authentication,jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader("Cache-Control","no-store");
    return directory.searchApi(new AdminUserDirectory.Query(offset,limit,search,"API",source,enabled,keyStatus,from,to==null?Instant.now():to),primary(authentication));
  }
  @GetMapping("/api/v1/admin/users/{id}/directory-detail")
  public AdminUserDirectory.Detail directoryDetail(@PathVariable("id") String id,Authentication authentication,
      jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader("Cache-Control","no-store");return directory.detail(id,primary(authentication));
  }
  private static boolean primary(Authentication authentication) {
    if(authentication==null||authentication.getAuthorities().stream().noneMatch(r->r.getAuthority().equals("ROLE_ADMIN")||r.getAuthority().equals("ROLE_NORMAL_ADMIN")))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return authentication.getAuthorities().stream().anyMatch(r->r.getAuthority().equals("ROLE_ADMIN"));
  }
  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Map<String,String> invalid(){return Map.of("code","INVALID_DIRECTORY_QUERY");}
  @ExceptionHandler(java.util.NoSuchElementException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public Map<String,String> missing(){return Map.of("code","USER_NOT_FOUND");}
  @ExceptionHandler({IllegalStateException.class,org.springframework.web.client.RestClientException.class})
  @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
  public Map<String,String> unavailable(){return Map.of("code","DIRECTORY_UNAVAILABLE");}
}
