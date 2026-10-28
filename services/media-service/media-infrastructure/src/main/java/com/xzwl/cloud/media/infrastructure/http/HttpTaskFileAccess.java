package com.xzwl.cloud.media.infrastructure.http;
import com.xzwl.cloud.media.application.task.TaskFileAccess;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;
public final class HttpTaskFileAccess implements TaskFileAccess {
  private final RestClient client;
  public HttpTaskFileAccess(RestClient client) {this.client=client;}
  public Access sign(String owner,String file) {
    try {
      var result=client.post().uri("/internal/v1/files/access-url").body(Map.of("ownerId",owner,"fileId",file)).retrieve().body(Access.class);
      if(result==null)throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
      return result;
    } catch(HttpClientErrorException.NotFound missing) {throw new ResponseStatusException(HttpStatus.NOT_FOUND);}
    catch(RestClientException unavailable) {throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);}
  }
}
