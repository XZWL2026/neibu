package com.xzwl.cloud.media.interfaces.http;

import com.xzwl.cloud.media.application.provider.ProviderDiagnosticQuery;
import com.xzwl.cloud.web.PrimaryAdministrator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/media/provider-logs")
public final class ProviderDiagnosticController {
  private final ProviderDiagnosticQuery query;
  public ProviderDiagnosticController(ProviderDiagnosticQuery query) {this.query=query;}

  @GetMapping public ResponseEntity<ProviderDiagnosticQuery.Page> list(
      @RequestParam(value="taskId",required=false) String taskId,
      @RequestParam(value="phase",required=false) String phase,
      @RequestParam(value="from",required=false) Long from,
      @RequestParam(value="to",required=false) Long to,
      @RequestParam(value="offset",defaultValue="0") int offset,
      @RequestParam(value="limit",defaultValue="20") int limit,HttpServletRequest request) {
    PrimaryAdministrator.require(request);
    long end=to==null?System.currentTimeMillis():to;
    taskId=blank(taskId);phase=blank(phase);
    long start=from==null?(taskId==null?Math.max(0,end-86400000):0):from;
    try {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.list(new ProviderDiagnosticQuery.Filter(taskId,phase,start,end,offset,limit)));}
    catch(IllegalArgumentException invalid) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid diagnostic filters");}
  }
  @GetMapping("/{id}") public ResponseEntity<ProviderDiagnosticQuery.Detail> detail(@PathVariable("id") String id,HttpServletRequest request) {
    PrimaryAdministrator.require(request);
    try {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.find(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND)));}
    catch(IllegalArgumentException invalid) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid diagnostic ID");}
  }
  @GetMapping("/tasks") public ResponseEntity<ProviderDiagnosticQuery.TaskPage> tasks(
      @RequestParam(value="taskId",required=false) String taskId,
      @RequestParam(value="phase",required=false) String phase,
      @RequestParam(value="from",required=false) Long from,
      @RequestParam(value="to",required=false) Long to,
      @RequestParam(value="offset",defaultValue="0") int offset,
      @RequestParam(value="limit",defaultValue="20") int limit,HttpServletRequest request) {
    PrimaryAdministrator.require(request);
    long end=to==null?System.currentTimeMillis():to;
    taskId=blank(taskId);phase=blank(phase);
    long start=from==null?(taskId==null?Math.max(0,end-86400000):0):from;
    try {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.tasks(new ProviderDiagnosticQuery.Filter(taskId,phase,start,end,offset,limit)));}
    catch(IllegalArgumentException invalid) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid diagnostic filters");}
  }
  private static String blank(String value) {return value==null || value.isBlank()?null:value.trim();}
}
