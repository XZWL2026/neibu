package com.xzwl.cloud.media.infrastructure.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.xzwl.cloud.media.domain.task.TaskRequest;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;

/** Task-scoped, bounded diagnostic evidence. Never used to decide billing or retry behavior. */
final class ProviderHttpDiagnostics implements AutoCloseable {
  private static final ThreadLocal<ProviderHttpDiagnostics> CURRENT=new ThreadLocal<>();
  private static final ObjectMapper JSON=new ObjectMapper();
  private static final Pattern URL=Pattern.compile("(?i)(?:https?://|asset://|data:)[^\\s\\\"<>]+" );
  private static final Pattern CREDENTIAL=Pattern.compile("(?i)(?:bearer\\s+[^\\s,;\\\"]+|(?:api[_-]?key|access[_-]?token|authorization|secret|signature|password)\\s*[=:]\\s*[^\\s,;]+)");
  private static final Set<String> SENSITIVE=Set.of("authorization","apikey","key","token","accesstoken","secret","secretkey","secretid","signature","password","cookie","setcookie","b64json","base64");
  private final ProviderHttpDiagnostics previous;
  private final Consumer<String> sink;
  private final List<String> secrets;
  private final ObjectNode context;

  static ProviderHttpDiagnostics open(ProviderJournal journal,String id,String phase,TaskRequest request,ProviderSettings settings) {
    return new ProviderHttpDiagnostics(detail->journal.httpDiagnostic(id,phase,detail),id,phase,request,
        List.of(settings.arkKey,settings.secretId,settings.secretKey));
  }
  ProviderHttpDiagnostics(Consumer<String> sink,String id,String phase,TaskRequest request,List<String> credentials) {
    this.sink=sink;
    secrets=new ArrayList<>(credentials.stream().filter(s->s!=null && !s.isEmpty()).toList());
    secrets.add(request.prompt());
    secrets.sort(Comparator.comparingInt(String::length).reversed());
    context=JSON.createObjectNode().put("taskId",id).put("phase",phase)
        .put("requestedResolution",request.resolution()).put("requestedAspectRatio",request.aspectRatio())
        .put("requestedDurationSeconds",request.durationSeconds())
        .put("referenceMode",request.referenceMode().name()).put("referenceCount",request.references().size())
        .put("promptCharacters",request.prompt().codePointCount(0,request.prompt().length()))
        .put("promptUtf8Bytes",request.prompt().getBytes(StandardCharsets.UTF_8).length)
        .put("promptSha256",digest(request.prompt()));
    previous=CURRENT.get();CURRENT.set(this);
  }
  static Exchange begin(String method,String endpoint,Map<String,String> headers,String body,int attempt,java.time.Duration timeout) {
    var current=CURRENT.get();
    if(current==null) return new Exchange(null,null,0);
    try {
      var event=current.context.deepCopy().put("exchangeId",UUID.randomUUID().toString())
          .put("method",method).put("endpoint",current.endpoint(endpoint)).put("attempt",attempt+1)
          .put("timeoutMs",timeout.toMillis()).put("requestBytes",body==null?0:body.getBytes(StandardCharsets.UTF_8).length)
          .put("requestSha256",body==null?null:digest(body));
      event.set("request",current.payload(body,true));
      // Request headers are deliberately not copied. Only the MPS action is useful and non-secret.
      headers.forEach((name,value)->{if(name.equalsIgnoreCase("X-TC-Action")) event.put("action",current.text(value));});
      if(!method.equals("GET")) current.emit(event.deepCopy().put("event","request_started"));
      return new Exchange(current,event,System.nanoTime());
    } catch(RuntimeException ignored) {
      LoggerFactory.getLogger(ProviderHttpDiagnostics.class).warn("provider_http_diagnostic_build_failed");
      return new Exchange(null,null,0);
    }
  }
  static final class Exchange {
    private final ProviderHttpDiagnostics owner;
    private final ObjectNode event;
    private final long start;
    Exchange(ProviderHttpDiagnostics owner,ObjectNode event,long start) { this.owner=owner;this.event=event;this.start=start; }
    void response(HttpResponse<String> response) {
      if(owner==null) return;
      try {
        // Suppress only ordinary nonterminal polls; terminal/error/unknown responses remain available.
        if(event.path("method").asText().equals("GET") && response.statusCode()>=200 && response.statusCode()<300) {
          JsonNode parsed=JSON.readTree(response.body());
          if(parsed!=null) {
            JsonNode state=parsed.has("status")?parsed:parsed.path("data");
            if(!parsed.hasNonNull("error") && !state.hasNonNull("error")
                && Set.of("queued","running","pending","processing").contains(state.path("status").asText())) return;
          }
        }
      } catch(Exception ignored) { /* Non-JSON is diagnostic evidence too. */ }
      try {
        var detail=event.deepCopy().put("event","response_received").put("httpStatus",response.statusCode())
            .put("elapsedMs",(System.nanoTime()-start)/1_000_000)
            .put("responseBytes",response.body()==null?0:response.body().getBytes(StandardCharsets.UTF_8).length);
        var headers=detail.putObject("responseHeaders");
        for(String name:List.of("x-request-id","x-tt-logid","request-id","x-trace-id","traceparent","content-type","retry-after"))
          response.headers().firstValue(name).ifPresent(value->headers.put(name,owner.text(value)));
        detail.set("response",owner.payload(response.body(),false));
        owner.emit(detail);
      } catch(RuntimeException ignored) { LoggerFactory.getLogger(ProviderHttpDiagnostics.class).warn("provider_http_diagnostic_build_failed"); }
    }
    void failure(String kind) {
      if(owner!=null) owner.emit(event.deepCopy().put("event","transport_failed").put("failureKind",kind)
          .put("elapsedMs",(System.nanoTime()-start)/1_000_000));
    }
  }
  private JsonNode payload(String raw,boolean request) {
    if(raw==null) return NullNode.getInstance();
    JsonNode clean;
    try { clean=sanitize(JSON.readTree(raw),"",request,0); }
    catch(Exception invalid) { clean=JSON.createObjectNode().put("nonJson",true).put("preview",text(raw)); }
    if(clean==null) return NullNode.getInstance();
    // Preserve valid JSON when bounding the overall record, including unusually large provider responses.
    String encoded=clean.toString();
    return encoded.length()>32768?JSON.createObjectNode().put("truncated",true).put("preview",encoded.substring(0,32768)):clean;
  }
  private JsonNode sanitize(JsonNode value,String name,boolean request,int depth) {
    if(value==null) return NullNode.getInstance();
    String key=name.replaceAll("[^A-Za-z]","").toLowerCase(Locale.ROOT);
    if(SENSITIVE.contains(key)) return TextNode.valueOf("<REDACTED>");
    if(key.equals("prompt") || request && key.equals("text"))
      return JSON.createObjectNode().put("characters",value.asText().codePointCount(0,value.asText().length()))
          .put("utf8Bytes",value.asText().getBytes(StandardCharsets.UTF_8).length).put("sha256",digest(value.asText()));
    if(depth>12) return TextNode.valueOf("<TRUNCATED_DEPTH>");
    if(value.isObject()) {
      var out=JSON.createObjectNode();var fields=value.fields();int count=0;
      while(fields.hasNext()) {
        var field=fields.next();if(count++>=100) {out.put("_truncated",true);break;}
        out.set(text(field.getKey()),sanitize(field.getValue(),field.getKey(),request,depth+1));
      }
      return out;
    }
    if(value.isArray()) {
      var out=JSON.createArrayNode();int count=0;
      for(var item:value) {if(count++>=100) {out.add("<TRUNCATED_ITEMS>");break;}out.add(sanitize(item,name,request,depth+1));}
      return out;
    }
    if(value.isTextual()) return TextNode.valueOf(text(value.asText()));
    return value;
  }
  private String text(String raw) {
    String result=raw;
    for(String secret:secrets) if(!secret.isEmpty()) result=result.replace(secret,"<REDACTED>");
    result=URL.matcher(result).replaceAll(match->{
      String url=match.group();String host="";
      try { host=Optional.ofNullable(URI.create(url).getHost()).orElse(""); } catch(Exception ignored) { }
      return java.util.regex.Matcher.quoteReplacement("<URL host="+host+" sha256="+digest(url)+">");
    });
    result=CREDENTIAL.matcher(result).replaceAll("<REDACTED>");
    result=result.replaceAll("[\\p{Cntrl}&&[^\\t]]"," ");
    return result.length()>16384?result.substring(0,16384)+"<TRUNCATED>":result;
  }
  private String endpoint(String raw) {
    try { var uri=URI.create(raw);String result=uri.getScheme()+"://"+uri.getHost()+(uri.getPort()<0?"":":"+uri.getPort())+uri.getPath();
      for(String secret:secrets) if(!secret.isEmpty()) result=result.replace(secret,"<REDACTED>");return result;
    } catch(Exception invalid) {return "<INVALID_ENDPOINT>";}
  }
  private static String digest(String value) {
    try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
  }
  private void emit(ObjectNode detail) {
    try {sink.accept(detail.put("recordedAt",System.currentTimeMillis()).toString());}
    catch(RuntimeException ignored) {LoggerFactory.getLogger(ProviderHttpDiagnostics.class).warn("provider_http_diagnostic_sink_failed");}
  }
  @Override public void close() {if(previous==null) CURRENT.remove();else CURRENT.set(previous);}
}
