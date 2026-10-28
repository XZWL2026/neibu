package com.xzwl.cloud.media.infrastructure.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.task.MediaReference;
import com.xzwl.cloud.media.domain.task.ReferenceMode;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.web.client.*;

public final class HttpStoragePort implements StoragePort {
  private static final System.Logger LOG=System.getLogger(HttpStoragePort.class.getName());
  private final RestClient client;
  public HttpStoragePort(RestClient client) { this.client=client; }
  @Override public List<MediaReference> resolveReferences(String owner, List<MediaReference> references) {
    return resolveReferences(owner,references,ReferenceMode.FIRST_LAST_FRAME);
  }
  @Override public List<MediaReference> resolveReferences(String owner,List<MediaReference> references,ReferenceMode mode) {
    return references.stream().map(reference -> {
      if (reference.fileId() == null) return reference;
      try {
        AccessUrl result=client.post().uri("/internal/v1/files/reference-media/access-url")
            .contentType(MediaType.APPLICATION_JSON).body(Map.of("ownerId",owner,"fileId",reference.fileId(),"omni",mode==ReferenceMode.OMNI))
            .retrieve().body(AccessUrl.class);
        if (result == null) throw new DefinitiveRejectionException("INVALID_REFERENCE_URL");
        var kind=switch(result.mimeType()==null ? "" : result.mimeType()) {
          case "image/png","image/jpeg" -> MediaReference.Kind.IMAGE;
          case "video/mp4","video/quicktime" -> MediaReference.Kind.VIDEO;
          case "audio/mpeg","audio/wav","audio/x-wav" -> MediaReference.Kind.AUDIO;
          default -> throw new DefinitiveRejectionException("INVALID_REFERENCE_TYPE");
        };
        if(kind!=reference.kind()) throw new DefinitiveRejectionException("REFERENCE_TYPE_MISMATCH");
        return new MediaReference(null, result.url(),kind,result.durationSeconds());
      } catch (RestClientResponseException response) {
        if (response.getStatusCode().is4xxClientError()) {
          if(response.getStatusCode().value()==400) {
            try {
              var error=response.getResponseBodyAs(StorageError.class);
              if(error!=null && error.message()!=null && error.message().contains("metadata") && error.message().contains("upload"))
                throw new DefinitiveRejectionException("REFERENCE_METADATA_REQUIRED");
            } catch(RestClientException ignored) { }
          }
          throw new DefinitiveRejectionException("REFERENCE_REJECTED");
        }
        throw new DependencyUnavailableException("storage unavailable");
      } catch (RestClientException unavailable) {
        throw new DependencyUnavailableException("storage unavailable");
      } catch (IllegalArgumentException invalid) {
        throw new DefinitiveRejectionException("INVALID_REFERENCE_URL");
      }
    }).toList();
  }
  public VideoSource probeVideo(String owner,String fileId) {
    try {
      var source=client.post().uri("/internal/v1/files/processing-videos/access-url").contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("ownerId",owner,"fileId",fileId)).retrieve().body(ProbedVideo.class);
      if(source==null) throw new DependencyUnavailableException("invalid storage probe");
      return new VideoSource(source.url(),source.mimeType(),source.durationSeconds(),source.width(),source.height());
    } catch(RestClientResponseException failure) {
      if(failure.getStatusCode().is4xxClientError()) throw new DefinitiveRejectionException("INVALID_ENHANCEMENT_SOURCE");
      throw new DependencyUnavailableException("storage unavailable");
    } catch(RestClientException failure) { throw new DependencyUnavailableException("storage unavailable"); }
  }
  @JsonIgnoreProperties(ignoreUnknown=true)
  public record ProbedVideo(String url,String mimeType,Double durationSeconds,Integer width,Integer height) {}
  @JsonIgnoreProperties(ignoreUnknown=true)
  public record AccessUrl(String url,String mimeType,Double durationSeconds) {}
  @Override public String archive(String owner,String taskId,int index,String url) {
    return archive(owner,taskId,index,url,ArchiveStage.RESULT);
  }
  @Override public String archive(String owner,String taskId,int index,String url,ArchiveStage stage) {
    MediaReference.https(url);
    MediaReference.identifier(taskId,"taskId");
    if(index<0) throw new IllegalArgumentException("output index must not be negative");
    String archiveKey=taskId+":"+Objects.requireNonNull(stage,"stage").name()+":"+index;
    try {
      StoredFile file=Objects.requireNonNull(client.post().uri("/internal/v1/files/media-results/archive-url")
          .contentType(MediaType.APPLICATION_JSON).body(Map.of("ownerId",owner,"sourceUrl",url,"archiveKey",archiveKey))
          .retrieve().body(StoredFile.class));
      if(!"MEDIA_RESULT".equals(file.purpose()) || file.sizeBytes()<=0)
        throw new DependencyUnavailableException("invalid storage response");
      return MediaReference.identifier(file.fileId(),"fileId");
    } catch(RestClientResponseException response) {
      String reason="STORAGE_HTTP_ERROR";
      try {
        var body=response.getResponseBodyAs(StorageError.class);
        if(body!=null && "archive URL host is not allowed".equals(body.message())) reason="ARCHIVE_HOST_NOT_ALLOWED";
      } catch(RuntimeException ignored) { /* Never include an untrusted response in logs. */ }
      LOG.log(System.Logger.Level.WARNING,"Media archive task={0} index={1} status={2} reason={3}",taskId,index,response.getStatusCode().value(),reason);
      if(response.getStatusCode().value()==400) throw new DefinitiveRejectionException("STORAGE_REJECTED");
      throw new DependencyUnavailableException("storage unavailable");
    } catch(RestClientException unavailable) { throw new DependencyUnavailableException("storage unavailable"); }
  }
  @JsonIgnoreProperties(ignoreUnknown=true)
  public record StorageError(String message) {}
  @JsonIgnoreProperties(ignoreUnknown=true)
  public record StoredFile(String fileId,String purpose,long sizeBytes) {}
}
