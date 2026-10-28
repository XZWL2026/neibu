package com.xzwl.cloud.payment.interfaces.http;

import com.xzwl.cloud.payment.application.workflow.*;
import com.xzwl.cloud.payment.application.identity.UserStatusPort;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
public final class CorporateTransferController {
  private final PaymentWorkflow workflow;
  private final UserStatusPort users;
  public CorporateTransferController(PaymentWorkflow workflow,UserStatusPort users){this.workflow=workflow;this.users=users;}
  @PostMapping(value="/api/v1/payments/corporate-transfers",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public PaymentController.View submit(@RequestPart("requestId") String requestId,@RequestPart("amount4") String amount4,
      @RequestPart(value="remark",required=false) String remark,@RequestPart("file") MultipartFile file,HttpServletRequest request)throws IOException{
    if(!request.isUserInRole("API_USER"))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    String owner=owner(request);
    if(!users.enabled(owner))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    if(amount4==null||amount4.length()>64)throw new IllegalArgumentException("invalid amount");
    if(file.isEmpty()||file.getSize()>TransferProof.MAX_BYTES)throw new IllegalArgumentException("receipt must be 1 byte to 10MB");
    try(var stream=file.getInputStream()){
      var proof=new TransferProof(file.getOriginalFilename(),file.getContentType(),stream.readNBytes(TransferProof.MAX_BYTES+1));
      return PaymentController.view(workflow.corporate(new PaymentWorkflow.CorporateRecharge(requestId,owner,new BigDecimal(amount4),remark,proof)));
    }
  }
  @GetMapping("/api/v1/admin/payments/corporate-transfers")
  public Page history(@RequestParam(value="page",defaultValue="0") int page,@RequestParam(value="size",defaultValue="20") int size,HttpServletRequest request,HttpServletResponse response){
    admin(request);response.setHeader("Cache-Control","no-store");
    var result=workflow.corporateHistory(page,size);
    return new Page(result.items().stream().map(t->new Transfer(PaymentController.view(t.order()),t.fileName(),t.contentType(),t.sizeBytes(),t.order().paidAt()==null?null:t.order().paidAt().toString())).toList(),result.total(),result.page(),result.size());
  }
  @GetMapping("/api/v1/admin/payments/corporate-transfers/{id}/proof")
  public Proof proof(@PathVariable("id") String id,HttpServletRequest request,HttpServletResponse response){
    admin(request);response.setHeader("Cache-Control","no-store");
    var proof=workflow.transferProof(id);
    return new Proof(proof.fileName(),proof.contentType(),Base64.getEncoder().encodeToString(proof.content()));
  }
  private static String owner(HttpServletRequest request){if(request.getUserPrincipal()==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);return request.getUserPrincipal().getName();}
  private static void admin(HttpServletRequest request){owner(request);if(!request.isUserInRole("ADMIN")&&!request.isUserInRole("NORMAL_ADMIN"))throw new ResponseStatusException(HttpStatus.FORBIDDEN);}
  public record Transfer(PaymentController.View order,String fileName,String contentType,int sizeBytes,String confirmedAt){}
  public record Page(List<Transfer> items,long total,int page,int size){}
  public record Proof(String fileName,String contentType,String base64){}
}
