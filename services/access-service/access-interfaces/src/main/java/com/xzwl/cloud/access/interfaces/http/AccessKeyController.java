package com.xzwl.cloud.access.interfaces.http;
import com.xzwl.cloud.access.application.key.*;
import com.xzwl.cloud.access.domain.key.AccountType;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
public final class AccessKeyController {
  private final AccessKeyStore store;private final AccessKeys keys;
  public AccessKeyController(AccessKeyStore store,AccessKeys keys){this.store=store;this.keys=keys;}
  @PostMapping("/internal/v1/access/directory-keys")
  public List<AccessKeyStore.DirectoryKey> directoryKeys(@RequestBody DirectoryRequest body) {
    return store.directoryKeys(body.ownerIds(),body.search());
  }
  public record DirectoryRequest(List<String> ownerIds,String search) {}
  @GetMapping("/api/v1/admin/access/keys")
  public List<View> adminList(@RequestParam(name="ownerId",required=false) String ownerId,HttpServletRequest request){admin(request);return store.list(ownerId).stream().map(AccessKeyController::view).toList();}
  @PostMapping("/api/v1/admin/access/keys/lookup")
  public View adminLookup(@RequestBody Lookup body,HttpServletRequest request,HttpServletResponse response){
    if(!request.isUserInRole("ADMIN"))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    response.setHeader("Cache-Control","no-store");
    return view(store.findBySecret(body.secret()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND)));
  }
  @GetMapping("/api/v1/admin/access/keys/{id}")
  public View adminGet(@PathVariable("id") String id,HttpServletRequest request){admin(request);return view(store.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND)));}
  @GetMapping("/api/v1/admin/access/keys/{id}/secret")
  public Map<String,String> adminSecret(@PathVariable("id") String id,HttpServletRequest request,HttpServletResponse response){
    if(!request.isUserInRole("ADMIN"))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    response.setHeader("Cache-Control","no-store");return Map.of("secret",store.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND)).secret());
  }
  @PostMapping("/internal/v1/access/admin-accounts")
  public Map<String,Boolean> manage(@RequestBody Managed body){
    if(body.createKey()==null)throw new IllegalArgumentException("createKey required");
    store.manage(body.requestId(),body.userId(),body.accountType(),body.createKey(),body.operatorId());return Map.of("success",true);
  }
  private static void admin(HttpServletRequest request){if(!request.isUserInRole("ADMIN")&&!request.isUserInRole("NORMAL_ADMIN"))throw new ResponseStatusException(HttpStatus.FORBIDDEN);}
  public record Managed(String requestId,String userId,AccountType accountType,Boolean createKey,String operatorId){}
  @GetMapping("/api/v1/access/keys")
  public List<View> list(HttpServletRequest request){
    denyPortal(request, true);
    return store.findByOwner(owner(request)).map(k->List.of(view(k))).orElseGet(List::of);
  }
  @PostMapping("/api/v1/access/keys")
  public View create(HttpServletRequest request){denyPortal(request, false);return view(keys.ownerKey(owner(request)));}
  @GetMapping("/api/v1/access/keys/{id}/secret")
  public Map<String,String> secret(@PathVariable("id") String id,HttpServletRequest request,HttpServletResponse response){
    denyPortal(request, true);
    var key=keys.ownerKey(owner(request));if(!key.id().equals(id))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    response.setHeader("Cache-Control","no-store");return Map.of("secret",key.secret());
  }
  @PatchMapping({"/api/v1/access/keys/{id}/status","/api/v1/admin/access/keys/{id}/status"})
  public View status(@PathVariable("id") String id,@RequestBody Status body,HttpServletRequest request){
    denyPortal(request, false);
    if(body.enabled==null)throw new IllegalArgumentException("enabled required");
    var key=store.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    if(!key.ownerId().equals(owner(request))&&!request.isUserInRole("ADMIN")&&!request.isUserInRole("NORMAL_ADMIN"))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    store.setEnabled(id,body.enabled);return view(store.findById(id).orElseThrow());
  }
  @PostMapping("/internal/v1/access/primary-keys")
  public View ensure(@RequestBody Paid body){return view(store.ensurePrimary(body.userId,body.accountType,body.orderId,body.paidAt));}
  @GetMapping("/internal/v1/access/keys/{id}")
  public View find(@PathVariable("id") String id){return view(keys.keyStatus(id));}
  @GetMapping("/internal/v1/access/owners/{ownerId}/primary-key")
  public View ownerKey(@PathVariable("ownerId") String ownerId){return view(keys.ownerKey(ownerId));}
  @GetMapping("/internal/v1/access/owners/{ownerId}/account-identity")
  public OwnerIdentity accountIdentity(@PathVariable("ownerId") String ownerId){
    return new OwnerIdentity(ownerId,store.findByOwner(ownerId).map(k->k.accountType().name()).orElse(null));
  }
  @PostMapping("/internal/v1/access/authorize")
  public View authorize(@RequestBody Authorization body){return view(keys.authorize(body.secret,body.capability));}
  private static void denyPortal(HttpServletRequest request, boolean allowApiRead) {
    if(request.isUserInRole("PROMOTER") || (!allowApiRead && request.isUserInRole("API_USER")))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }
  private static String owner(HttpServletRequest request){
    if(request.getUserPrincipal()==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return request.getUserPrincipal().getName();
  }
  private static View view(AccessKey k){return new View(k.id(),k.ownerId(),k.accountType().name(),k.kind(),k.enabled(),k.firstRechargeOrderId(),masked(k.secret()));}
  private static String masked(String secret){
    return secret!=null && secret.matches("sk-[A-Za-z0-9_-]{5,}") ? secret.substring(0,secret.length()-4)+"****" : null;
  }
  public record View(String keyId,String ownerId,String accountType,String kind,boolean enabled,String firstRechargeOrderId,String maskedKey){}
  public record OwnerIdentity(String ownerId,String accountType){}
  public record Paid(String userId,AccountType accountType,String orderId,java.time.Instant paidAt){}
  public record Status(Boolean enabled){}
  public record Lookup(String secret){@Override public String toString(){return "Lookup[redacted]";}}
  public record Authorization(String secret,String capability){@Override public String toString(){return "Authorization[redacted]";}}
}
