package com.xzwl.cloud.storage.interfaces.http;

import com.xzwl.cloud.storage.application.file.ArchiveUrlHandler;
import com.xzwl.cloud.storage.application.file.CreateAccessUrlHandler;
import com.xzwl.cloud.storage.application.file.CreateAccessUrlResult;
import com.xzwl.cloud.storage.application.file.FileAccessDeniedException;
import com.xzwl.cloud.storage.application.file.FileStorageException;
import com.xzwl.cloud.storage.application.file.FileValidationException;
import com.xzwl.cloud.storage.application.file.StoreFileCommand;
import com.xzwl.cloud.storage.application.file.StoreFileHandler;
import com.xzwl.cloud.storage.application.file.StoredFileNotFoundException;
import com.xzwl.cloud.storage.domain.file.FilePurpose;
import com.xzwl.cloud.storage.domain.file.StoredFile;
import java.io.IOException;
import java.io.InputStream;
import java.security.Principal;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
public final class StorageController {
  private final StoreFileHandler storeFiles;
  private final ArchiveUrlHandler archiveUrls;
  private final CreateAccessUrlHandler accessUrls;

  public StorageController(
      StoreFileHandler storeFiles,
      ArchiveUrlHandler archiveUrls,
      CreateAccessUrlHandler accessUrls) {
    this.storeFiles = storeFiles;
    this.archiveUrls = archiveUrls;
    this.accessUrls = accessUrls;
  }

  @PostMapping(
      value = "/api/v1/files/reference-images",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public StoredFileResponse uploadReferenceImage(
      @RequestPart("file") MultipartFile file, Principal principal) {
    return upload(owner(principal), FilePurpose.REFERENCE_IMAGE, file);
  }

  @PostMapping(value = "/api/v1/files/reference-media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public StoredFileResponse uploadReferenceMedia(@RequestPart("file") MultipartFile file, Principal principal) {
    String ownerId = owner(principal);
    try (InputStream body = file.getInputStream()) {
      return response(storeFiles.handleReferenceMedia(ownerId, file.getContentType(), file.getSize(), body));
    } catch (IOException failure) {
      throw new FileStorageException("multipart upload stream could not be opened", failure);
    }
  }

  @PostMapping(value="/api/v1/files/processing-videos",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public StoredFileResponse uploadProcessingVideo(@RequestPart("file") MultipartFile file,Principal principal) {
    try(InputStream body=file.getInputStream()) { return response(storeFiles.handleProcessingVideo(owner(principal),file.getContentType(),file.getSize(),body)); }
    catch(IOException failure) { throw new FileStorageException("video upload could not be read",failure); }
  }
  @PostMapping("/internal/v1/files/processing-videos/access-url")
  public CreateAccessUrlHandler.ReferenceAccessUrlResult processingVideoAccess(@RequestBody ReferenceAccessRequest request,Principal principal) {
    owner(principal); return accessUrls.handleProcessingVideo(request.fileId(),request.ownerId());
  }

  @PostMapping("/internal/v1/files/reference-media/access-url")
  public CreateAccessUrlHandler.ReferenceAccessUrlResult referenceMediaAccessUrl(
      @RequestBody ReferenceMediaAccessRequest request, Principal principal) {
    owner(principal);
    return accessUrls.handleReferenceMedia(request.fileId(), request.ownerId(), Boolean.TRUE.equals(request.omni()));
  }

  @PostMapping(
      value = "/api/v1/files/avatars",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public StoredFileResponse uploadAvatar(
      @RequestPart("file") MultipartFile file, Principal principal) {
    return upload(owner(principal), FilePurpose.AVATAR, file);
  }

  @GetMapping("/api/v1/files/avatars/{fileId}/access-url")
  public AccessUrlResponse avatarAccessUrl(@PathVariable("fileId") String fileId, Principal principal) {
    return response(accessUrls.handle(fileId, owner(principal), FilePurpose.AVATAR));
  }

  @GetMapping("/api/v1/files/{fileId}/access-url")
  public AccessUrlResponse createAccessUrl(
      @PathVariable("fileId") String fileId, Principal principal) {
    return response(accessUrls.handle(fileId, owner(principal)));
  }

  @GetMapping("/api/v1/files/{fileId}/image-content")
  public ResponseEntity<CreateAccessUrlHandler.ImageContent> imageContent(
      @PathVariable("fileId") String fileId, Principal principal) {
    return ResponseEntity.ok().header("Cache-Control", "no-store")
        .body(accessUrls.imageContent(fileId, owner(principal)));
  }

  @GetMapping("/api/v1/files/{fileId}/video-content")
  public ResponseEntity<CreateAccessUrlHandler.VideoContent> videoContent(
      @PathVariable("fileId") String fileId, Principal principal) {
    return ResponseEntity.ok().header("Cache-Control", "no-store")
        .body(accessUrls.videoContent(fileId, owner(principal)));
  }

  @PostMapping("/api/v1/files/{fileId}/reference-clip")
  public StoredFileResponse referenceClip(@PathVariable("fileId") String fileId,
      @RequestBody ReferenceClipRequest request, Principal principal) {
    return response(storeFiles.clipReferenceVideo(owner(principal),fileId,request.maxDurationSeconds()));
  }
  public record ReferenceClipRequest(int maxDurationSeconds) {}

  @PostMapping("/api/v1/files/{fileId}/match")
  public FileMatchResponse matchImage(@PathVariable("fileId") String fileId,
      @RequestBody FileMatchRequest request, Principal principal) {
    return new FileMatchResponse(accessUrls.matchesImage(fileId, owner(principal), request.sizeBytes(), request.sha256()));
  }

  public record FileMatchRequest(long sizeBytes, String sha256) {}
  public record FileMatchResponse(boolean matches) {}

  @PostMapping(
      value = "/internal/v1/files/media-results",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public StoredFileResponse archiveMediaResult(
      @RequestParam("ownerId") String ownerId,
      @RequestPart("file") MultipartFile file,
      Principal principal) {
    owner(principal);
    return upload(ownerId, FilePurpose.MEDIA_RESULT, file);
  }

  @PostMapping(
      value = "/internal/v1/files/media-results/archive-url",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public StoredFileResponse archiveMediaResultUrl(
      @RequestBody ArchiveUrlRequest request, Principal principal) {
    owner(principal);
    return response(archiveUrls.handle(request.ownerId(), request.sourceUrl(), request.archiveKey()));
  }

  @PostMapping("/internal/v1/files/reference-images/access-url")
  public AccessUrlResponse referenceAccessUrl(@RequestBody ReferenceAccessRequest request, Principal principal) {
    owner(principal);
    return response(accessUrls.handle(request.fileId(), request.ownerId(), FilePurpose.REFERENCE_IMAGE));
  }

  @PostMapping("/internal/v1/files/access-url")
  public AccessUrlResponse taskFileAccessUrl(@RequestBody ReferenceAccessRequest request, Principal principal) {
    owner(principal);
    return response(accessUrls.handleExisting(request.fileId(), request.ownerId()));
  }

  public record ReferenceAccessRequest(String ownerId, String fileId) {}

  public record ReferenceMediaAccessRequest(String ownerId, String fileId, Boolean omni) {}

  @ExceptionHandler(FileValidationException.class)
  public ResponseEntity<ErrorResponse> invalid(FileValidationException failure) {
    return ResponseEntity.badRequest().body(new ErrorResponse(failure.getMessage()));
  }

  @ExceptionHandler(FileAccessDeniedException.class)
  public ResponseEntity<ErrorResponse> forbidden(FileAccessDeniedException failure) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(new ErrorResponse(failure.getMessage()));
  }

  @ExceptionHandler(StoredFileNotFoundException.class)
  public ResponseEntity<ErrorResponse> notFound(StoredFileNotFoundException failure) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ErrorResponse(failure.getMessage()));
  }

  @ExceptionHandler(FileStorageException.class)
  public ResponseEntity<ErrorResponse> storageFailed(FileStorageException failure) {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(new ErrorResponse(failure.getMessage()));
  }

  private StoredFileResponse upload(String ownerId, FilePurpose purpose, MultipartFile file) {
    try (InputStream body = file.getInputStream()) {
      return response(storeFiles.handle(new StoreFileCommand(
          ownerId, purpose, file.getContentType(), file.getSize(), body)));
    } catch (IOException failed) {
      throw new FileStorageException("multipart upload stream could not be opened", failed);
    }
  }

  private static String owner(Principal principal) {
    if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    return principal.getName();
  }

  private static StoredFileResponse response(StoredFile file) {
    return new StoredFileResponse(
        file.fileId(), file.mimeType(), file.sizeBytes(), file.purpose(), file.createdAt(),
        file.durationSeconds(), file.width(), file.height(), file.fps());
  }

  private static AccessUrlResponse response(CreateAccessUrlResult result) {
    return new AccessUrlResponse(result.url(), result.expiresAt());
  }

  public record ArchiveUrlRequest(String ownerId, String sourceUrl, String archiveKey) {}

  public record StoredFileResponse(
      String fileId, String mimeType, long sizeBytes, FilePurpose purpose, Instant createdAt,
      Double durationSeconds, Integer width, Integer height, Double fps) {}

  public record AccessUrlResponse(String url, Instant expiresAt) {}

  public record ErrorResponse(String message) {}
}
