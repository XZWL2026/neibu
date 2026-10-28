package com.xzwl.cloud.billing.interfaces.http;
import com.xzwl.cloud.billing.application.ledger.*;
import com.xzwl.cloud.billing.domain.wallet.WalletBalance;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
public final class BillingWalletController {
  private final BillingLedgerStore ledger;private final KeyOwnershipPort keys;
  public BillingWalletController(BillingLedgerStore ledger,KeyOwnershipPort keys){this.ledger=ledger;this.keys=keys;}
  @GetMapping("/api/v1/billing/wallets/{keyId}")
  public Balance balance(@PathVariable("keyId")String key,HttpServletRequest request){authorize(key,request);return view(ledger.balance(key));}
  @GetMapping("/api/v1/billing/wallets/{keyId}/ledger")
  public List<BillingLedgerStore.Entry> entries(@PathVariable("keyId")String key,@RequestParam(name="limit",defaultValue="50")int limit,HttpServletRequest request){
    authorize(key,request);return ledger.entries(key,admin(request),limit);
  }
  @GetMapping("/api/v1/admin/billing/wallets/{keyId}/adjustments")
  public List<BillingLedgerStore.Adjustment> adjustments(@PathVariable("keyId")String key,HttpServletRequest request){
    if(request.getUserPrincipal()==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if(!admin(request))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    keys.find(key);
    return ledger.adjustments(key);
  }
  private void authorize(String key,HttpServletRequest request){
    if(request.getUserPrincipal()==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    var owner=keys.find(key);
    if(!admin(request)&&(!owner.ownerId().equals(request.getUserPrincipal().getName())||!owner.enabled()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }
  static boolean admin(HttpServletRequest request){return request.isUserInRole("ADMIN")||request.isUserInRole("NORMAL_ADMIN");}
  static Balance view(WalletBalance b){return new Balance(b.walletId(),b.availablePoints4().toString(),b.version());}
  public record Balance(String walletId,String availablePoints4,long version){}
}
