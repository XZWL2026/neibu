package com.xzwl.cloud.payment.interfaces.http;
import com.xzwl.cloud.payment.application.workflow.*;
import com.xzwl.cloud.payment.domain.order.RechargeType;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
public final class PaymentController {
  private final PaymentWorkflow workflow;
  private final com.xzwl.cloud.payment.application.identity.UserStatusPort users;
  public PaymentController(PaymentWorkflow workflow,com.xzwl.cloud.payment.application.identity.UserStatusPort users){this.workflow=workflow;this.users=users;}
  @GetMapping("/internal/v1/payments/accounts/{id}/identity")
  public Identity identity(@PathVariable("id") String id) { return new Identity(id,workflow.accountIdentity(id)); }
  @PostMapping("/internal/v1/payments/account-identities")
  public List<PaymentWorkflowStore.AccountIdentity> directoryIdentities(@RequestBody List<String> userIds) { return workflow.storedAccountIdentities(userIds); }
  public record Identity(String ownerId,String accountType) {}
  @GetMapping("/api/v1/admin/payments/account-identities")
  public List<PaymentWorkflowStore.AccountIdentity> adminIdentities(@RequestParam("userIds") List<String> userIds,HttpServletRequest request){
    admin(request);return workflow.accountIdentities(userIds);
  }
  @PatchMapping("/api/v1/admin/payments/accounts/{id}")
  public Options adminAccount(@PathVariable("id") String id,@RequestBody AccountEdit body,HttpServletRequest request){
    admin(request);knownUser(id);if(body.accountType()==null||body.createKey()==null)throw new IllegalArgumentException("account type and createKey required");
    return options(workflow.adminAccount(id,body.requestId(),body.accountType().name(),body.createKey(),owner(request),primary(request)));
  }
  public enum ManagedType { PERSONAL, ENTERPRISE, API }
  public record AccountEdit(String requestId,ManagedType accountType,Boolean createKey){}
  @GetMapping("/api/v1/payments/recharge-options")
  public Options options(HttpServletRequest request){user(request);return options(workflow.options(owner(request)));}
  @GetMapping("/api/v1/payments/recharges")
  public List<View> history(HttpServletRequest request){user(request);return workflow.history(owner(request)).stream().map(PaymentController::view).toList();}
  @GetMapping("/api/v1/admin/payments/api-creation-options")
  public Options apiCreationOptions(HttpServletRequest request){
    admin(request);if(!primary(request))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return options(workflow.apiCreationOptions());
  }
  @GetMapping("/api/v1/admin/payments/recharge-options")
  public Options adminOptions(@RequestParam("userId") String userId,HttpServletRequest request){admin(request);if(userId==null||!users.exists(userId))throw new IllegalArgumentException("known user required");return options(workflow.options(userId));}
  @GetMapping("/api/v1/admin/payments/recharges")
  public List<View> adminHistory(@RequestParam(name="userId",required=false) String userId,HttpServletRequest request){
    admin(request);
    if(userId!=null&&!userId.matches("[A-Za-z0-9._:-]{1,128}"))throw new IllegalArgumentException("invalid user ID");
    return (userId==null?workflow.adminHistory():workflow.history(userId)).stream().map(PaymentController::view).toList();
  }
  public List<View> adminHistory(HttpServletRequest request){return adminHistory(null,request);}
  @GetMapping("/api/v1/admin/payments/orders")
  public AdminPage adminOrders(@RequestParam(name="offset",defaultValue="0") int offset,@RequestParam(name="limit",defaultValue="100") int limit,
      @RequestParam("to") Instant to,HttpServletRequest request,HttpServletResponse response){
    admin(request);
    if(offset<0||limit<1||limit>100||to==null)throw new IllegalArgumentException("invalid order page");
    try{to.toEpochMilli();}catch(ArithmeticException invalid){throw new IllegalArgumentException("invalid cutoff");}
    response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
    var page=workflow.adminOrders(offset,limit,to);
    return new AdminPage(page.items().stream().map(o->new AdminOrder(view(o),o.paidAt()==null?null:o.paidAt().toString(),
      o.transactionId(),o.providerOrderId(),o.keyId(),o.refundReference(),o.refundedAt()==null?null:o.refundedAt().toString())).toList(),page.total(),page.offset(),page.limit());
  }
  public record AdminOrder(View order,String paidAt,String transactionId,String providerOrderId,String keyId,String refundReference,String refundedAt){}
  public record AdminPage(List<AdminOrder> items,long total,int offset,int limit){}
  @PostMapping("/api/v1/admin/payments/recharges")
  public View manual(@RequestBody ManualRecharge body,HttpServletRequest request){
    admin(request);knownUser(body.userId);
    return view(workflow.manual(new PaymentWorkflow.ManualRecharge(body.requestId,body.userId,decimal(body.amount4),body.accountType,body.enterpriseName,body.remark,owner(request)),primary(request)));
  }
  @PostMapping("/api/v1/admin/payments/recharges/{id}/confirm")
  public View confirmManual(@PathVariable("id") String id,HttpServletRequest request){
    admin(request);
    var order=workflow.adminOrder(id);knownUser(order.userId());
    return view(workflow.confirmManual(id,owner(request),primary(request)));
  }
  @PostMapping("/api/v1/admin/payments/recharges/{id}/reject")
  public View rejectManual(@PathVariable("id") String id,HttpServletRequest request){
    admin(request);return view(workflow.rejectManual(id,owner(request)));
  }
  private void knownUser(String id){
    if(id==null||!id.matches("[A-Za-z0-9._:-]{1,128}")||!users.enabled(id))throw new IllegalArgumentException("known enabled user required");
  }
  private static void user(HttpServletRequest request){if(!request.isUserInRole("USER")&&!request.isUserInRole("API_USER"))throw new ResponseStatusException(HttpStatus.FORBIDDEN);}
  private static boolean primary(HttpServletRequest request){return request.isUserInRole("ADMIN")&&request.isUserInRole("PRIMARY_ADMIN");}
  private static void admin(HttpServletRequest request){
    if(!request.isUserInRole("ADMIN")&&!request.isUserInRole("NORMAL_ADMIN"))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }
  private static Options options(PaymentWorkflow.Options o){return new Options(o.userId(),o.accountType(),o.firstRecharge(),o.pointsPerYuan4(),o.rules().stream().map(r->new Rule(r.accountType(),r.minimumAmount4(),r.presetAmounts4())).toList());}
  @PostMapping("/api/v1/payments/recharges") public View create(@RequestBody NewRecharge body,HttpServletRequest request){
    user(request);
    if(request.isUserInRole("API_USER")&&body.accountType()!=RechargeType.API)throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return view(workflow.create(new PaymentWorkflow.NewRecharge(body.requestId,owner(request),decimal(body.amount4),body.accountType,body.enterpriseName,body.paymentMethod)));
  }
  @GetMapping("/api/v1/payments/recharges/{id}")public View get(@PathVariable("id")String id,HttpServletRequest request){user(request);return view(workflow.get(id,owner(request)));}
  @PostMapping("/api/v1/payments/recharges/{id}/refund")@ResponseStatus(HttpStatus.ACCEPTED)
  public View refund(@PathVariable("id")String id,HttpServletRequest request){if(request.isUserInRole("API_USER")||request.isUserInRole("PROMOTER"))throw new ResponseStatusException(HttpStatus.FORBIDDEN);return view(workflow.refund(id,owner(request)));}
  @PostMapping(value="/callbacks/payment",produces=MediaType.TEXT_PLAIN_VALUE) public String callback(HttpServletRequest request)throws IOException{
    byte[] raw=request.getInputStream().readNBytes(65537);
    if(raw.length>65536)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
    var headers=new LinkedHashMap<String,List<String>>();
    var names=request.getHeaderNames();while(names.hasMoreElements()){String name=names.nextElement();headers.put(name,List.copyOf(Collections.list(request.getHeaders(name))));}
    workflow.callback(raw,Map.copyOf(headers));return "success";
  }
  private static String owner(HttpServletRequest request){if(request.getUserPrincipal()==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);return request.getUserPrincipal().getName();}
  private static BigDecimal decimal(String value){if(value==null||value.length()>64)throw new IllegalArgumentException("invalid amount");return new BigDecimal(value);}
  static View view(RechargeOrder o){return new View(o.id(),o.amount4().toPlainString(),o.rateSnapshot4().toPlainString(),o.rechargeType().name(),
    o.status(),o.fulfilled(),o.checkoutUrl(),o.expiresAt().toString(),o.userId(),o.source(),o.createdAt().toString(),
    o.amount4().multiply(o.rateSnapshot4()).setScale(4,java.math.RoundingMode.DOWN).toPlainString(),o.remark(),o.submittedBy(),o.confirmedBy(),o.paymentMethod(),o.rejectedBy(),o.rejectedAt()==null?null:o.rejectedAt().toString());}
  public record NewRecharge(String requestId,String amount4,RechargeType accountType,String enterpriseName,com.xzwl.cloud.payment.domain.order.PaymentMethod paymentMethod){
    public NewRecharge(String requestId,String amount4,RechargeType accountType,String enterpriseName){this(requestId,amount4,accountType,enterpriseName,null);}
  }
  public record View(String orderId,String amount4,String rateSnapshot4,String accountType,String status,boolean fulfilled,String checkoutUrl,String expiresAt,
      String userId,String source,String createdAt,String expectedPoints4,String remark,String submittedBy,String confirmedBy,com.xzwl.cloud.payment.domain.order.PaymentMethod paymentMethod,String rejectedBy,String rejectedAt){}
  public record ManualRecharge(String userId,String requestId,String amount4,RechargeType accountType,String enterpriseName,String remark){}
  public record Options(String userId,String accountType,boolean firstRecharge,String pointsPerYuan4,List<Rule> rules){}
  public record Rule(String accountType,String minimumAmount4,List<String> presetAmounts4){}
}
