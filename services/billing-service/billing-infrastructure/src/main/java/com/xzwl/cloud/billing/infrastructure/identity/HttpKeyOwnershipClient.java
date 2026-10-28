package com.xzwl.cloud.billing.infrastructure.identity;
import com.xzwl.cloud.billing.application.ledger.KeyOwnershipPort;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.*;
public final class HttpKeyOwnershipClient implements KeyOwnershipPort, com.xzwl.cloud.billing.application.ledger.MediaBilling.Keys {
  private final RestClient client;
  public HttpKeyOwnershipClient(String url,String token) { this(RestClient.builder(),url,token); }
  public HttpKeyOwnershipClient(RestClient.Builder builder,String url,String token){
    var f=new SimpleClientHttpRequestFactory();f.setConnectTimeout(Duration.ofSeconds(2));f.setReadTimeout(Duration.ofSeconds(3));
    client=builder.clone().baseUrl(url).defaultHeader("X-Service-Token",token).requestFactory(f).build();
  }
  public com.xzwl.cloud.billing.application.ledger.MediaBilling.Key forOwner(String ownerId){
    try {
      var key=client.get().uri("/internal/v1/access/owners/{id}/primary-key",ownerId).retrieve()
          .body(com.xzwl.cloud.billing.application.ledger.MediaBilling.Key.class);
      if(key==null||!ownerId.equals(key.ownerId()))throw new IllegalStateException("invalid owner key response");
      return key;
    } catch(RestClientResponseException failed) {
      // No user/key eligibility is a definite refusal; transport, service auth and 5xx can recover.
      if(failed.getStatusCode().value()==403||failed.getStatusCode().value()==409)
        throw new IllegalArgumentException("media key unavailable");
      throw failed;
    }
  }
  public com.xzwl.cloud.billing.application.ledger.MediaBilling.Key forAdministrationOwner(String ownerId) {
    var rows=client.post().uri("/internal/v1/access/directory-keys").body(java.util.Map.of("ownerIds",java.util.List.of(ownerId),"search",""))
        .retrieve().body(DirectoryKey[].class);
    if(rows==null||rows.length!=1||!ownerId.equals(rows[0].ownerId())||rows[0].keyId()==null)throw new IllegalArgumentException("API owner required");
    var key=client.get().uri("/internal/v1/access/keys/{id}",rows[0].keyId()).retrieve().body(com.xzwl.cloud.billing.application.ledger.MediaBilling.Key.class);
    if(key==null||!ownerId.equals(key.ownerId())||!rows[0].keyId().equals(key.keyId()))throw new IllegalStateException("invalid owner key response");
    return key;
  }
  public record DirectoryKey(String ownerId,String keyId) {}
  public Key find(String id){
    var result=client.get().uri("/internal/v1/access/keys/{id}",id).retrieve().body(Key.class);
    if(result==null||!id.equals(result.keyId())||result.ownerId()==null)throw new IllegalStateException("invalid key response");
    return result;
  }
}
