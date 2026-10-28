package com.xzwl.cloud.media.infrastructure.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.task.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.*;

/** Native worker sees authenticated bytes, never an arbitrary client URL or filesystem path. */
public final class HttpDepthPort implements DepthTasks.Port {
  private final RestClient storage;
  private final URI worker;
  private final String token;
  private final ObjectMapper json;
  private final Duration timeout;
  private final HttpClient http;
  private static final long MAX_BYTES=200L*1024*1024;
  public HttpDepthPort(RestClient storage,String worker,String token,ObjectMapper json,Duration timeout) {
    this.storage=storage;this.worker=URI.create(worker);this.token=token;this.json=json;this.timeout=timeout;
    if(!"http".equals(this.worker.getScheme()) || !"127.0.0.1".equals(this.worker.getHost())
        || this.worker.getUserInfo()!=null || this.worker.getQuery()!=null || this.worker.getFragment()!=null
        || !Set.of("","/").contains(this.worker.getPath())) throw new IllegalArgumentException("depth worker must be loopback HTTP");
    this.http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
  }
  @Override public DepthTasks.Source source(String owner,String file) {
    try {
      var source=storage.post().uri("/internal/v1/files/processing-videos/access-url")
          .contentType(MediaType.APPLICATION_JSON).body(Map.of("ownerId",owner,"fileId",file)).retrieve().body(SourceAccess.class);
      if(source==null || source.sizeBytes()<=0 || source.sizeBytes()>MAX_BYTES || source.url()==null)
        throw new DefinitiveRejectionException("INVALID_DEPTH_SOURCE");
      var uri=URI.create(source.url());
      if(!("http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getUserInfo()==null && uri.getFragment()==null)) MediaReference.https(source.url());
      return new DepthTasks.Source(source.url(),source.mimeType(),source.sizeBytes(),source.durationSeconds(),source.width(),source.height(),source.fps());
    } catch(RestClientResponseException error) { throw responseFailure(error.getStatusCode().value()); }
    catch(RestClientException error) { throw unavailable(); }
  }
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
  record SourceAccess(String url,String mimeType,long sizeBytes,Double durationSeconds,Integer width,Integer height,Double fps) {}
  @Override public void submit(com.xzwl.cloud.media.domain.task.MediaTask task) {
    if(state(task,true)!=null) return;
    var source=source(task.ownerId(),file(task));
    if(!Objects.equals(source.durationSeconds(),task.data().request().references().getFirst().durationSeconds()))
      throw new DefinitiveRejectionException("DEPTH_SOURCE_CHANGED");
    Path input=null;
    try {
      input=download(HttpRequest.newBuilder(URI.create(source.url())).timeout(timeout).GET().build(),MAX_BYTES,source.sizeBytes());
      var request=request("/jobs/"+task.id()).header("Content-Type",source.mimeType())
          .header("X-Owner-Id",task.ownerId()).header("X-Source-File-Id",file(task))
          .header("X-Resolution",task.data().request().resolution()).POST(HttpRequest.BodyPublishers.ofFile(input)).build();
      var response=http.send(request,HttpResponse.BodyHandlers.discarding());
      if(response.statusCode()!=200 && response.statusCode()!=202) throw responseFailure(response.statusCode());
    } catch(IOException failure) { throw unavailable(); }
    catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw unavailable(); }
    finally { delete(input); }
  }
  @Override public String poll(com.xzwl.cloud.media.domain.task.MediaTask task) {
    var state=state(task,false);return state.status();
  }
  @Override public String archive(com.xzwl.cloud.media.domain.task.MediaTask task) {
    if(!"COMPLETE".equals(state(task,false).status())) throw unavailable();
    Path output=null;
    try {
      output=download(request("/jobs/"+task.id()+"/result").GET().build(),MAX_BYTES,-1);
      var body=new LinkedMultiValueMap<String,Object>();
      body.add("ownerId",task.ownerId());
      body.add("file",new org.springframework.http.HttpEntity<>(new FileSystemResource(output),
          org.springframework.http.HttpHeaders.readOnlyHttpHeaders(headers())));
      var file=storage.post().uri("/internal/v1/files/media-results").contentType(MediaType.MULTIPART_FORM_DATA)
          .body(body).retrieve().body(HttpStoragePort.StoredFile.class);
      if(file==null || !"MEDIA_RESULT".equals(file.purpose()) || file.sizeBytes()<=0) throw unavailable();
      return MediaReference.identifier(file.fileId(),"fileId");
    } catch(RestClientResponseException error) { throw responseFailure(error.getStatusCode().value()); }
    catch(RestClientException|IOException error) { throw unavailable(); }
    catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw unavailable(); }
    finally { delete(output); }
  }
  private static org.springframework.http.HttpHeaders headers() {
    var headers=new org.springframework.http.HttpHeaders();headers.setContentType(MediaType.valueOf("video/mp4"));return headers;
  }
  private State state(com.xzwl.cloud.media.domain.task.MediaTask task,boolean missingAllowed) {
    try {
      var response=http.send(request("/jobs/"+task.id()).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
      try(var input=response.body();var deadline=deadline(input)) {
        if(response.statusCode()==404 && missingAllowed) return null;
        if(response.statusCode()!=200) throw responseFailure(response.statusCode());
        byte[] bytes=input.readNBytes(65537);if(bytes.length>65536) throw unavailable();
        var state=json.readValue(bytes,State.class);
        if(!task.ownerId().equals(state.owner()) || !file(task).equals(state.sourceFileId())
            || !task.data().request().resolution().equals(state.resolution())) throw new DefinitiveRejectionException("DEPTH_IDENTITY_MISMATCH");
        if(!Set.of("QUEUED","RUNNING","COMPLETE","FAILED").contains(state.status())) throw unavailable();
        return state;
      }
    } catch(IOException error) { throw unavailable(); }
    catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw unavailable(); }
  }
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
  record State(String status,String owner,String sourceFileId,String resolution) {}
  private HttpRequest.Builder request(String path) {
    if(token.length()<32) throw unavailable();
    return HttpRequest.newBuilder(worker.resolve(path)).timeout(timeout).header("Authorization","Bearer "+token);
  }
  private Path download(HttpRequest request,long maximum,long expected) throws IOException,InterruptedException {
    var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
    Path target=null;
    try(var input=response.body();var deadline=deadline(input)) {
      if(response.statusCode()!=200) throw responseFailure(response.statusCode());
      long declared=response.headers().firstValueAsLong("Content-Length").orElse(-1);
      if(declared>maximum || expected>=0 && declared>=0 && declared!=expected) throw new DefinitiveRejectionException("INVALID_DEPTH_BYTES");
      target=Files.createTempFile("xzwl-depth-",".mp4");
      try(var output=Files.newOutputStream(target)) {
        byte[] buffer=new byte[65536];long total=0;int count;
        while((count=input.read(buffer))!=-1) { total+=count;if(total>maximum) throw new DefinitiveRejectionException("DEPTH_FILE_TOO_LARGE");output.write(buffer,0,count); }
        if(total<=0 || expected>=0 && total!=expected) throw new DefinitiveRejectionException("INVALID_DEPTH_BYTES");
      }
      return target;
    } catch(IOException|RuntimeException error) { delete(target);throw error; }
  }
  private AutoCloseableDeadline deadline(InputStream input) { return new AutoCloseableDeadline(input,timeout); }
  private static final class AutoCloseableDeadline implements AutoCloseable {
    private final Thread timer;
    AutoCloseableDeadline(InputStream input,Duration timeout) {
      timer=Thread.startVirtualThread(()->{ try { Thread.sleep(timeout);input.close(); } catch(InterruptedException cancelled) { Thread.currentThread().interrupt(); } catch(IOException ignored) { } });
    }
    public void close() { timer.interrupt(); }
  }
  private static String file(com.xzwl.cloud.media.domain.task.MediaTask task) { return task.data().request().references().getFirst().fileId(); }
  private static RuntimeException responseFailure(int status) {
    return status==400 || status==403 || status==409 || status==422
        ? new DefinitiveRejectionException("DEPTH_REJECTED") : unavailable();
  }
  private static DependencyUnavailableException unavailable() { return new DependencyUnavailableException("local depth worker unavailable"); }
  private static void delete(Path path) { if(path!=null) try { Files.deleteIfExists(path); } catch(IOException error) { throw unavailable(); } }
}
