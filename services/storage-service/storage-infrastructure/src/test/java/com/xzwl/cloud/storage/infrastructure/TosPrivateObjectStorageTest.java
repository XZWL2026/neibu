package com.xzwl.cloud.storage.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.volcengine.tos.TOSV2;
import com.volcengine.tos.comm.HttpMethod;
import com.volcengine.tos.comm.common.ACLType;
import com.volcengine.tos.model.object.DeleteObjectInput;
import com.volcengine.tos.model.object.DeleteObjectOutput;
import com.volcengine.tos.model.object.GetObjectBasicOutput;
import com.volcengine.tos.model.object.HeadObjectV2Input;
import com.volcengine.tos.model.object.HeadObjectV2Output;
import com.volcengine.tos.model.object.PreSignedURLInput;
import com.volcengine.tos.model.object.PreSignedURLOutput;
import com.volcengine.tos.model.object.PutObjectInput;
import com.volcengine.tos.model.object.PutObjectOutput;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TosPrivateObjectStorageTest {
  @Test void jpgFilenameIsIncludedInTheSignedAttachmentResponse() {
    var sign = new AtomicReference<PreSignedURLInput>();
    TOSV2 client = (TOSV2) Proxy.newProxyInstance(TOSV2.class.getClassLoader(), new Class<?>[] {TOSV2.class}, (proxy, method, args) -> {
      assertEquals("preSignedURL", method.getName());
      sign.set((PreSignedURLInput) args[0]);
      return new PreSignedURLOutput("https://signed.example/jpeg", java.util.Map.of());
    });
    var storage = new TosPrivateObjectStorage(client, "private-bucket");
    assertEquals("https://signed.example/jpeg", storage.signGet("objects/legacy", 3600, "file-1.jpg"));
    assertEquals("attachment; filename=\"file-1.jpg\"", sign.get().getQuery().get("response-content-disposition"));
    assertEquals("objects/legacy", sign.get().getKey());
    assertEquals("private-bucket", sign.get().getBucket());
    assertEquals(HttpMethod.GET, sign.get().getHttpMethod());
    assertEquals(3600, sign.get().getExpires());
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
        () -> storage.signGet("objects/legacy", 3600, "file.jpg\"\r\nX-Injected: true"));
  }

  @Test void onlyAnExplicitMissingObjectAllowsAnotherUpload() {
    for (String code : java.util.List.of("NotFound", "NoSuchKey", "NoSuchBucket", "AccessDenied", "InternalError")) {
      int status = code.equals("AccessDenied") ? 403 : code.equals("InternalError") ? 500 : 404;
      TOSV2 client = (TOSV2) Proxy.newProxyInstance(TOSV2.class.getClassLoader(), new Class<?>[] {TOSV2.class}, (proxy, method, args) -> {
        assertEquals("headObject", method.getName());
        throw new com.volcengine.tos.TosServerException(status).setCode(code);
      });
      var storage = new TosPrivateObjectStorage(client, "private-bucket");
      if (code.equals("NotFound") || code.equals("NoSuchKey")) assertTrue(storage.find("objects/archive").isEmpty());
      else org.junit.jupiter.api.Assertions.assertThrows(com.xzwl.cloud.storage.application.file.FileStorageException.class,
          () -> storage.find("objects/archive"));
    }
    TOSV2 client = (TOSV2) Proxy.newProxyInstance(TOSV2.class.getClassLoader(), new Class<?>[] {TOSV2.class}, (proxy, method, args) -> {
      throw new com.volcengine.tos.TosClientException("network unavailable", null);
    });
    org.junit.jupiter.api.Assertions.assertThrows(com.xzwl.cloud.storage.application.file.FileStorageException.class,
        () -> new TosPrivateObjectStorage(client, "private-bucket").find("objects/archive"));
  }

  @Test void readsTheConfiguredPrivateObjectThroughTheSdk() throws Exception {
    var content = new ByteArrayInputStream(new byte[] {1, 2, 3});
    TOSV2 client = (TOSV2) Proxy.newProxyInstance(TOSV2.class.getClassLoader(), new Class<?>[] {TOSV2.class}, (proxy, method, args) -> {
      assertEquals("getObject", method.getName());
      var input = (com.volcengine.tos.model.object.GetObjectV2Input) args[0];
      assertEquals("private-bucket", input.getBucket());
      assertEquals("objects/existing", input.getKey());
      return new com.volcengine.tos.model.object.GetObjectV2Output(new GetObjectBasicOutput(), content);
    });
    try (var stream = new TosPrivateObjectStorage(client, "private-bucket").open("objects/existing")) {
      assertSame(content, stream);
      assertEquals(3, stream.readAllBytes().length);
    }
  }

  @Test
  void usesPrivateStreamingPutHeadDeleteAndOneHourGetSignature() throws Exception {
    var put = new AtomicReference<PutObjectInput>();
    var head = new AtomicReference<HeadObjectV2Input>();
    var delete = new AtomicReference<DeleteObjectInput>();
    var sign = new AtomicReference<PreSignedURLInput>();
    TOSV2 client = (TOSV2) Proxy.newProxyInstance(
        TOSV2.class.getClassLoader(), new Class<?>[] {TOSV2.class}, (proxy, method, args) -> {
          return switch (method.getName()) {
            case "putObject" -> {
              put.set((PutObjectInput) args[0]);
              yield new PutObjectOutput().setEtag("put-etag");
            }
            case "headObject" -> {
              head.set((HeadObjectV2Input) args[0]);
              yield headOutput("image/png", 12, "head-etag");
            }
            case "deleteObject" -> {
              delete.set((DeleteObjectInput) args[0]);
              yield new DeleteObjectOutput();
            }
            case "preSignedURL" -> {
              sign.set((PreSignedURLInput) args[0]);
              yield new PreSignedURLOutput("https://signed.example/object", java.util.Map.of());
            }
            default -> null;
          };
        });
    var storage = new TosPrivateObjectStorage(client, "private-bucket");
    var body = new ByteArrayInputStream(new byte[12]);

    assertEquals("put-etag", storage.putPrivate("objects/file-1", "image/png", 12, body));
    var stored = storage.head("objects/file-1");
    storage.delete("objects/file-1");
    assertEquals("https://signed.example/object", storage.signGet("objects/file-1", 3600));

    assertEquals("private-bucket", put.get().getBucket());
    assertEquals("objects/file-1", put.get().getKey());
    assertEquals(12, put.get().getContentLength());
    assertSame(body, put.get().getContent());
    assertTrue(put.get().isForbidOverwrite());
    assertEquals("image/png", put.get().getOptions().getContentType());
    assertEquals(ACLType.ACL_PRIVATE, put.get().getOptions().getAclType());
    assertEquals("private-bucket", head.get().getBucket());
    assertEquals("objects/file-1", head.get().getKey());
    assertEquals("image/png", stored.contentType());
    assertEquals(12, stored.contentLength());
    assertEquals("head-etag", stored.etag());
    assertEquals("private-bucket", delete.get().getBucket());
    assertEquals("objects/file-1", delete.get().getKey());
    assertEquals(HttpMethod.GET, sign.get().getHttpMethod());
    assertEquals(3600, sign.get().getExpires());
    assertEquals("private-bucket", sign.get().getBucket());
    assertEquals("objects/file-1", sign.get().getKey());
    assertTrue(sign.get().getQuery().isEmpty());
  }

  private static HeadObjectV2Output headOutput(String mimeType, long size, String etag)
      throws ReflectiveOperationException {
    var basic = new GetObjectBasicOutput();
    setField(basic, "contentType", mimeType);
    setField(basic, "contentLength", size);
    setField(basic, "etag", etag);
    var output = new HeadObjectV2Output();
    setField(output, "headObjectBasicOutput", basic);
    return output;
  }

  private static void setField(Object target, String name, Object value)
      throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}
