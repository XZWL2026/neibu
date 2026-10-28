package com.xzwl.cloud.media.infrastructure.provider;

import com.fasterxml.jackson.databind.*;
import com.xzwl.cloud.media.application.task.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Bounded JSON requests, no redirect or POST retry, and no raw upstream exception propagation. */
public final class ProviderHttp {
  private final HttpClient client;
  private final ObjectMapper json;
  private final Duration timeout;
  public ProviderHttp(HttpClient client,ObjectMapper json,Duration timeout) {
    if(timeout.isNegative() || timeout.isZero() || client.followRedirects()!=HttpClient.Redirect.NEVER)
      throw new IllegalArgumentException("invalid provider HTTP policy");
    this.client=client; this.json=json; this.timeout=timeout;
  }
  String encode(Object body) {
    try { return json.writeValueAsString(body); }
    catch(Exception invalid) { throw new IllegalArgumentException("invalid provider request"); }
  }
  JsonNode call(String method,String endpoint,Map<String,String> headers,String body) {
    return call(method,endpoint,headers,body,timeout,0);
  }
  JsonNode call(String method,String endpoint,Map<String,String> headers,String body,Integer timeoutSeconds,Integer retryCount) {
    if(timeoutSeconds!=null && (timeoutSeconds<1 || timeoutSeconds>180)) throw new IllegalArgumentException("invalid request timeout");
    return call(method,endpoint,headers,body,timeoutSeconds==null?timeout:Duration.ofSeconds(timeoutSeconds),retryCount);
  }
  JsonNode call(String method,String endpoint,Map<String,String> headers,String body,Duration budget,Integer retryCount) {
    int retries=retryCount==null?0:retryCount;
    if(budget==null || budget.isZero() || budget.isNegative() || retries<0 || retries>3)
      throw new IllegalArgumentException("invalid provider HTTP policy");
    Duration limited=budget.compareTo(timeout)<0?budget:timeout;
    long deadline=System.nanoTime()+limited.toNanos();
    for(int attempt=0;;attempt++) {
      long remaining=deadline-System.nanoTime();
      if(remaining<=0) throw uncertain();
      HttpResponse<String> response;
      var diagnostic=ProviderHttpDiagnostics.begin(method,endpoint,headers,body,attempt,Duration.ofNanos(remaining));
      try {
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofNanos(remaining));
        headers.forEach(request::header);
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8));
        response=client.send(request.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      } catch(InterruptedException interrupted) { diagnostic.failure("InterruptedException");Thread.currentThread().interrupt();throw uncertain();
      } catch(java.io.IOException unavailable) {
        diagnostic.failure(unavailable.getClass().getSimpleName());
        if(method.equals("GET") && attempt<retries) continue;
        throw uncertain();
      } catch(RuntimeException invalid) {diagnostic.failure(invalid.getClass().getSimpleName());throw uncertain();}
      diagnostic.response(response);
      int status=response.statusCode();
      if(!method.equals("GET") && status>=400 && status<500 && status!=408 && status!=409 && status!=429)
        throw new DefinitiveRejectionException(rejectionCode(response.body()));
      if(status<200 || status>=300) {
        if(method.equals("GET") && attempt<retries && (status==408 || status==429 || status>=500 && status<=599)) continue;
        throw uncertain();
      }
      try {
        JsonNode parsed=json.readTree(response.body());
        if(parsed==null || !parsed.isObject()) throw uncertain();
        return parsed;
      } catch(Exception malformed) {throw uncertain();}
    }
  }
  private String rejectionCode(String body) {
    try {
      String code=json.readTree(body).path("error").path("code").asText("PROVIDER_REJECTED");
      return code.matches("[A-Za-z0-9_.-]{1,256}") ? code : "PROVIDER_REJECTED";
    } catch(Exception invalid) { return "PROVIDER_REJECTED"; }
  }
  static DependencyUnavailableException uncertain() { return new DependencyUnavailableException("provider response unavailable or uncertain"); }
}
