package com.xzwl.cloud.storage.infrastructure;

import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TosException;
import com.volcengine.tos.comm.HttpMethod;
import com.volcengine.tos.comm.common.ACLType;
import com.volcengine.tos.model.object.DeleteObjectInput;
import com.volcengine.tos.model.object.HeadObjectV2Input;
import com.volcengine.tos.model.object.ObjectMetaRequestOptions;
import com.volcengine.tos.model.object.PreSignedURLInput;
import com.volcengine.tos.model.object.PutObjectInput;
import com.xzwl.cloud.storage.application.file.FileStorageException;
import com.xzwl.cloud.storage.application.file.PrivateObjectStorage;
import java.io.InputStream;
import java.util.Objects;

public final class TosPrivateObjectStorage implements PrivateObjectStorage {
  private final java.util.function.Supplier<TOSV2> clients;
  private final String bucket;

  public TosPrivateObjectStorage(TOSV2 client, String bucket) {
    Objects.requireNonNull(client, "client");
    this.clients = () -> client;
    if (bucket == null || bucket.isBlank()) {
      throw new IllegalArgumentException("bucket must not be blank");
    }
    this.bucket = bucket;
  }

  /** A missing deployment configuration must not prevent the admin status endpoint starting. */
  public TosPrivateObjectStorage(java.util.function.Supplier<TOSV2> clients,String bucket) {
    this.clients=Objects.requireNonNull(clients);this.bucket=bucket;
  }
  private TOSV2 client() {
    if(bucket==null || bucket.isBlank()) throw new FileStorageException("TOS configuration unavailable");
    return clients.get();
  }

  @Override
  public String putPrivate(
      String objectKey, String mimeType, long contentLength, InputStream body) {
    var options = new ObjectMetaRequestOptions()
        .setContentType(mimeType)
        .setAclType(ACLType.ACL_PRIVATE);
    var input = new PutObjectInput()
        .setBucket(bucket)
        .setKey(objectKey)
        .setContentLength(contentLength)
        .setForbidOverwrite(true)
        .setOptions(options)
        .setContent(body);
    try {
      return client().putObject(input).getEtag();
    } catch (TosException failed) {
      throw new FileStorageException("TOS private object upload failed", failed);
    }
  }

  @Override
  public StoredObject head(String objectKey) {
    try {
      var output = client().headObject(
          new HeadObjectV2Input().setBucket(bucket).setKey(objectKey));
      return new StoredObject(
          objectKey, output.getContentType(), output.getContentLength(), output.getEtag());
    } catch (TosException failed) {
      throw new FileStorageException("TOS object HEAD failed", failed);
    }
  }

  @Override
  public java.util.Optional<StoredObject> find(String objectKey) {
    try {
      return java.util.Optional.of(head(objectKey));
    } catch (FileStorageException failed) {
      if (failed.getCause() instanceof com.volcengine.tos.TosServerException response
          && response.getStatusCode() == 404
          && (com.volcengine.tos.comm.Code.NOT_FOUND.equals(response.getCode())
              || com.volcengine.tos.comm.Code.NO_SUCH_KEY.equals(response.getCode())))
        return java.util.Optional.empty();
      throw failed;
    }
  }

  @Override
  public InputStream open(String objectKey) {
    try {
      return client().getObject(new com.volcengine.tos.model.object.GetObjectV2Input()
          .setBucket(bucket).setKey(objectKey)).getContent();
    } catch (TosException failed) {
      throw new FileStorageException("TOS private object read failed", failed);
    }
  }

  @Override
  public void delete(String objectKey) {
    try {
      client().deleteObject(new DeleteObjectInput().setBucket(bucket).setKey(objectKey));
    } catch (TosException failed) {
      throw new FileStorageException("TOS object cleanup failed", failed);
    }
  }

  @Override
  public String signGet(String objectKey, long expiresSeconds) {
    return signGetWithQuery(objectKey, expiresSeconds, java.util.Map.of());
  }

  @Override
  public String signGet(String objectKey, long expiresSeconds, String downloadFilename) {
    if (downloadFilename == null || !downloadFilename.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")) {
      throw new IllegalArgumentException("invalid download filename");
    }
    return signGetWithQuery(objectKey, expiresSeconds, java.util.Map.of(
        "response-content-disposition", "attachment; filename=\"" + downloadFilename + "\""));
  }

  private String signGetWithQuery(String objectKey, long expiresSeconds, java.util.Map<String, String> query) {
    try {
      return client().preSignedURL(new PreSignedURLInput()
          .setHttpMethod(HttpMethod.GET)
          .setBucket(bucket)
          .setKey(objectKey)
          .setQuery(query)
          .setExpires(expiresSeconds))
          .getSignedUrl();
    } catch (TosException failed) {
      throw new FileStorageException("TOS signed URL creation failed", failed);
    }
  }
}
