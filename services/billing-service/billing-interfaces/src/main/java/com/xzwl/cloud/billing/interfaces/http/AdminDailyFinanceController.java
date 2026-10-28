package com.xzwl.cloud.billing.interfaces.http;

import com.xzwl.cloud.billing.application.ledger.DailyFinanceQuery;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/billing/daily-finance")
public final class AdminDailyFinanceController {
  private final DailyFinanceQuery query;
  public AdminDailyFinanceController(DailyFinanceQuery query){this.query=query;}
  @GetMapping public ResponseEntity<DailyFinanceQuery.Report> dailyFinance(HttpServletRequest request,
      @RequestParam(name="from",required=false) String from,@RequestParam(name="to",required=false) String to,
      @RequestParam(name="offset",defaultValue="0") int offset,@RequestParam(name="limit",defaultValue="30") int limit) {
    AdminConsumptionController.authorize(request);
    LocalDate start=null,end=null;
    try{
      if(offset<0||limit<1||limit>30||(from==null)!=(to==null))throw new IllegalArgumentException();
      if(from!=null){
        if(!from.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||!to.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException();
        start=LocalDate.parse(from);end=LocalDate.parse(to);
        if(start.getYear()<1||start.isAfter(end))throw new IllegalArgumentException();
      }
    }catch(DateTimeException|IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid daily finance query");}
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.query(start,end,offset,limit));
  }
}
