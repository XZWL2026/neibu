package com.xzwl.cloud.access.infrastructure.identity;
import com.xzwl.cloud.access.application.identity.SessionVerifier;
import com.xzwl.cloud.access.application.key.UserStatusPort;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.*;
public final class HttpIdentityClient implements SessionVerifier,UserStatusPort {
  private final RestClient iam,users;private final String token;
  public HttpIdentityClient(String iamUrl,String userUrl,String token){this(RestClient.builder(),iamUrl,userUrl,token);}
  public HttpIdentityClient(RestClient.Builder builder,String iamUrl,String userUrl,String token){iam=client(builder,iamUrl);users=client(builder,userUrl);this.token=token;}
  public Identity verify(String cookie,String csrf,boolean mutation){
    if(cookie==null||cookie.isBlank())throw new Rejected(401);
    if(mutation&&(csrf==null||csrf.isBlank()))throw new Rejected(403);
    try{
      RestClient.RequestHeadersSpec<?> request=mutation?iam.post().uri("/internal/v1/iam/session/verify").body(Map.of())
          :iam.get().uri("/internal/v1/iam/session");
      request.header("Cookie",cookie);if(csrf!=null)request.header("X-CSRF-TOKEN",csrf);
      var result=request.retrieve().body(Session.class);
      if(result==null||!result.authenticated||result.user==null||result.roles==null||result.user.id==null)throw new Rejected(401);
      if(result.roles.stream().anyMatch(r->!Set.of("user","admin","normal_admin","api_user").contains(r)))throw new Rejected(403);
      if((result.roles.contains("user")||result.roles.contains("api_user"))&&!enabled(result.user.id))throw new Rejected(401);
      return new Identity(result.user.id,result.roles.stream().map(r->r.toUpperCase(Locale.ROOT)).collect(Collectors.toSet()));
    }catch(RestClientResponseException e){throw new Rejected(e.getStatusCode().value()==401?401:e.getStatusCode().value()==403?403:503);}
    catch(RestClientException e){throw new Rejected(503);}
  }
  public boolean enabled(String id){
    try{
      var user=users.get().uri("/internal/v1/users/{id}",id).header("X-IAM-INTERNAL-TOKEN",token).retrieve().body(User.class);
      return user!=null&&id.equals(user.id)&&user.enabled;
    }catch(HttpClientErrorException.NotFound e){return false;}
    catch(RestClientException e){throw new Rejected(503);}
  }
  private static RestClient client(RestClient.Builder builder,String url){
    var f=new SimpleClientHttpRequestFactory();f.setConnectTimeout(Duration.ofSeconds(2));f.setReadTimeout(Duration.ofSeconds(3));
    return builder.clone().baseUrl(url).requestFactory(f).build();
  }
  public record Session(boolean authenticated,List<String> roles,User user){}
  public record User(String id,boolean enabled){}
}
