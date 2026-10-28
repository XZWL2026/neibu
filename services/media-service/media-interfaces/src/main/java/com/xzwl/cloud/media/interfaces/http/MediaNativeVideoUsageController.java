package com.xzwl.cloud.media.interfaces.http;

import com.xzwl.cloud.media.application.task.NativeVideoUsageQuery;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/media/native-video-usage")
public final class MediaNativeVideoUsageController {
  private final NativeVideoUsageQuery query;
  public MediaNativeVideoUsageController(NativeVideoUsageQuery query) {this.query=query;}
  @GetMapping public ResponseEntity<NativeVideoUsageQuery.Report> nativeVideoUsage(
      @RequestParam("from") String from,@RequestParam("to") String to,HttpServletRequest request) {
    if(request.getUserPrincipal()==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if(!request.isUserInRole("ADMIN") && !request.isUserInRole("NORMAL_ADMIN")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    LocalDate start,end;
    try {
      if(!from.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || !to.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
      start=LocalDate.parse(from);end=LocalDate.parse(to);
      if(start.isAfter(end)) throw new IllegalArgumentException();
    } catch(DateTimeException | IllegalArgumentException invalid) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid date range");}
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.query(start,end));
  }
}
