package com.xzwl.cloud.storage.interfaces.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.storage.application.file.ArchiveUrlHandler;
import com.xzwl.cloud.storage.application.file.CreateAccessUrlHandler;
import com.xzwl.cloud.storage.application.file.FileMetadataStore;
import com.xzwl.cloud.storage.application.file.FileStorageException;
import com.xzwl.cloud.storage.application.file.PrivateObjectStorage;
import com.xzwl.cloud.storage.application.file.RemoteFileSource;
import com.xzwl.cloud.storage.application.file.StoreFileHandler;
import com.xzwl.cloud.storage.domain.file.StoredFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StorageControllerTest {
  @Test void readsOwnedImageContentAsPrivateJsonAndRejectsOtherFiles() throws Exception {
    mvc.perform(multipart("/api/v1/files/reference-images").file(file("r.png", "image/png", PNG)).principal(() -> "user-1"));
    mvc.perform(multipart("/internal/v1/files/media-results").file(file("r.jpg", "image/jpeg", JPEG))
        .param("ownerId", "user-1").principal(() -> "media-service"));
    mvc.perform(multipart("/api/v1/files/avatars").file(file("a.png", "image/png", PNG)).principal(() -> "user-1"));
    mvc.perform(multipart("/internal/v1/files/media-results").file(file("v.mp4", "video/mp4", MP4))
        .param("ownerId", "user-1").principal(() -> "media-service"));
    for(int id=1;id<=2;id++) {
      var response=mvc.perform(get("/api/v1/files/{fileId}/image-content", "file-"+id).principal(() -> "user-1"))
          .andReturn().getResponse();
      assertEquals(200,response.getStatus());
      assertEquals("no-store",response.getHeader("Cache-Control"));
      var body=json.readTree(response.getContentAsString());
      assertEquals(2,body.size());
      assertEquals(id==1?"image/png":"image/jpeg",body.path("mimeType").asText());
      assertEquals(java.util.Base64.getEncoder().encodeToString(id==1?PNG:JPEG),body.path("base64").asText());
    }
    assertEquals(401,mvc.perform(get("/api/v1/files/file-1/image-content")).andReturn().getResponse().getStatus());
    assertEquals(403,mvc.perform(get("/api/v1/files/file-1/image-content").principal(() -> "user-2")).andReturn().getResponse().getStatus());
    for(String id:java.util.List.of("file-3","file-4"))
      assertEquals(403,mvc.perform(get("/api/v1/files/{fileId}/image-content",id).principal(() -> "user-1")).andReturn().getResponse().getStatus());
    assertEquals(404,mvc.perform(get("/api/v1/files/missing/image-content").principal(() -> "user-1")).andReturn().getResponse().getStatus());
  }

  @Test void readsOwnedVideoContentWithoutCrossOriginAccess() throws Exception {
    mvc.perform(multipart("/internal/v1/files/media-results").file(file("v.mp4", "video/mp4", MP4))
        .param("ownerId", "user-1").principal(() -> "media-service"));
    String path="/api/v1/files/file-1/video-content";
    var response=mvc.perform(get(path).principal(() -> "user-1")).andReturn().getResponse();
    assertEquals(200,response.getStatus());
    assertEquals("no-store",response.getHeader("Cache-Control"));
    assertEquals("video/mp4",json.readTree(response.getContentAsString()).path("mimeType").asText());
    assertEquals(java.util.Base64.getEncoder().encodeToString(MP4),json.readTree(response.getContentAsString()).path("base64").asText());
    assertEquals(401,mvc.perform(get(path)).andReturn().getResponse().getStatus());
    assertEquals(403,mvc.perform(get(path).principal(() -> "user-2")).andReturn().getResponse().getStatus());
    assertEquals(404,mvc.perform(get(path.replace("file-1","missing")).principal(() -> "user-1")).andReturn().getResponse().getStatus());
  }

  @Test void matchesAnOwnedOriginalWithoutUploadingOrChangingIt() throws Exception {
    mvc.perform(multipart("/api/v1/files/reference-images").file(file("r.png", "image/png", PNG)).principal(() -> "user-1"));
    String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(PNG));
    String body = json.writeValueAsString(Map.of("sizeBytes", PNG.length, "sha256", digest));
    String path = "/api/v1/files/file-1/match";
    var matched = mvc.perform(post(path).principal(() -> "user-1").contentType("application/json").content(body)).andReturn().getResponse();
    assertEquals(200, matched.getStatus());
    assertEquals(true, json.readTree(matched.getContentAsString()).path("matches").asBoolean());
    var different = mvc.perform(post(path).principal(() -> "user-1").contentType("application/json").content(body.replace(digest, "0".repeat(64)))).andReturn().getResponse();
    assertEquals(200, different.getStatus());
    assertEquals(false, json.readTree(different.getContentAsString()).path("matches").asBoolean());
    assertEquals(401, mvc.perform(post(path).contentType("application/json").content(body)).andReturn().getResponse().getStatus());
    assertEquals(403, mvc.perform(post(path).principal(() -> "user-2").contentType("application/json").content(body)).andReturn().getResponse().getStatus());
    assertEquals(404, mvc.perform(post(path.replace("file-1", "missing")).principal(() -> "user-1").contentType("application/json").content(body)).andReturn().getResponse().getStatus());
    assertEquals(400, mvc.perform(post(path).principal(() -> "user-1").contentType("application/json").content(body.replace(digest, "invalid"))).andReturn().getResponse().getStatus());
    assertEquals(400, mvc.perform(post(path).principal(() -> "user-1").contentType("application/json").content("{\"sizeBytes\":0,\"sha256\":\""+digest+"\"}")).andReturn().getResponse().getStatus());
    mvc.perform(multipart("/api/v1/files/avatars").file(file("a.png", "image/png", PNG)).principal(() -> "user-1"));
    assertEquals(403, mvc.perform(post(path.replace("file-1", "file-2")).principal(() -> "user-1").contentType("application/json").content(body)).andReturn().getResponse().getStatus());
    assertEquals(2, objects.objects.size());
    objects.objects.clear();
    assertEquals(502, mvc.perform(post(path).principal(() -> "user-1").contentType("application/json").content(body)).andReturn().getResponse().getStatus());
  }

  @Test void avatarAccessRejectsOtherOwnersAndReferenceFiles() throws Exception {
    var avatar=mvc.perform(multipart("/api/v1/files/avatars").file(file("a.png","image/png",PNG)).principal(()->"user-1")).andReturn().getResponse();
    String id=json.readTree(avatar.getContentAsString()).path("fileId").asText();
    String url="/api/v1/files/avatars/"+id+"/access-url";
    assertEquals(200,mvc.perform(get(url).principal(()->"user-1")).andReturn().getResponse().getStatus());
    assertEquals(403,mvc.perform(get(url).principal(()->"user-2")).andReturn().getResponse().getStatus());
    var reference=mvc.perform(multipart("/api/v1/files/reference-images").file(file("r.png","image/png",PNG)).principal(()->"user-1")).andReturn().getResponse();
    String other=json.readTree(reference.getContentAsString()).path("fileId").asText();
    assertEquals(403,mvc.perform(get("/api/v1/files/avatars/"+other+"/access-url").principal(()->"user-1")).andReturn().getResponse().getStatus());
  }

  @Test void processingVideoNormalizesMp4MovMimeFromVerifiedBytesOnly() throws Exception {
    for (String[] sample : new String[][] {{"clip.mov", "video/mp4", "video/quicktime"}, {"clip.mp4", "video/quicktime", "video/mp4"}}) {
      byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(getClass().getResource("/reference-media/" + sample[0]).toURI()));
      var response = mvc.perform(multipart("/api/v1/files/processing-videos")
          .file(file("browser-video.mp4", sample[1], bytes)).principal(() -> "user-1")).andReturn().getResponse();
      assertEquals(201, response.getStatus(), response.getContentAsString());
      var stored = json.readTree(response.getContentAsString());
      assertEquals(sample[2], stored.path("mimeType").asText());
      assertEquals("PROCESSING_VIDEO", stored.path("purpose").asText());
      assertEquals(3.0, stored.path("durationSeconds").asDouble(), 0.1);
      assertEquals(400, mvc.perform(multipart("/api/v1/files/reference-media")
          .file(file("browser-video.mp4", sample[1], bytes)).principal(() -> "user-1")).andReturn().getResponse().getStatus());
    }
    assertEquals(400, mvc.perform(multipart("/api/v1/files/processing-videos")
        .file(file("fake.mp4", "video/mp4", PNG)).principal(() -> "user-1")).andReturn().getResponse().getStatus());
  }

  @Test void clipsOwnedLongVideoAndPreservesOriginal() throws Exception {
    var path=java.nio.file.Files.createTempFile("replica-long-",".mp4");
    try {
      var process=new ProcessBuilder("ffmpeg","-v","error","-y","-f","lavfi","-i","color=s=640x640:r=24:d=31.5",
          "-f","lavfi","-i","sine=frequency=440:duration=31.5","-c:v","libx264","-preset","ultrafast","-c:a","aac",path.toString()).start();
      org.junit.jupiter.api.Assertions.assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0,process.exitValue());
      byte[] original=java.nio.file.Files.readAllBytes(path);
      var uploaded=mvc.perform(multipart("/api/v1/files/processing-videos").file(file("long.mp4","video/mp4",original)).principal(()->"user-1")).andReturn().getResponse();
      assertEquals(201,uploaded.getStatus());
      var json=new ObjectMapper(); String id=json.readTree(uploaded.getContentAsString()).path("fileId").asText();
      String endpoint="/api/v1/files/"+id+"/reference-clip", body="{\"maxDurationSeconds\":15}";
      var clipped=mvc.perform(post(endpoint).principal(()->"user-1").contentType("application/json").content(body)).andReturn().getResponse();
      assertEquals(200,clipped.getStatus());
      var result=json.readTree(clipped.getContentAsString());
      String clipId=result.path("fileId").asText();
      org.junit.jupiter.api.Assertions.assertNotEquals(id,clipId);
      assertEquals("REFERENCE_VIDEO",result.path("purpose").asText());
      org.junit.jupiter.api.Assertions.assertTrue(result.path("durationSeconds").asDouble()<=15 && result.path("durationSeconds").asDouble()>=14);
      assertEquals(200,mvc.perform(post("/internal/v1/files/reference-media/access-url").principal(()->"media-service").contentType("application/json")
          .content("{\"ownerId\":\"user-1\",\"fileId\":\""+clipId+"\",\"omni\":true}")).andReturn().getResponse().getStatus());
      var unchanged=mvc.perform(get("/api/v1/files/"+id+"/video-content").principal(()->"user-1")).andReturn().getResponse();
      org.junit.jupiter.api.Assertions.assertArrayEquals(original,java.util.Base64.getDecoder().decode(json.readTree(unchanged.getContentAsString()).path("base64").asText()));
      assertEquals(401,mvc.perform(post(endpoint).contentType("application/json").content(body)).andReturn().getResponse().getStatus());
      assertEquals(403,mvc.perform(post(endpoint).principal(()->"user-2").contentType("application/json").content(body)).andReturn().getResponse().getStatus());
      assertEquals(404,mvc.perform(post(endpoint.replace(id,"missing")).principal(()->"user-1").contentType("application/json").content(body)).andReturn().getResponse().getStatus());
      for(int seconds:new int[]{0,1,31}) assertEquals(400,mvc.perform(post(endpoint).principal(()->"user-1").contentType("application/json").content("{\"maxDurationSeconds\":"+seconds+"}")).andReturn().getResponse().getStatus());
    } finally {java.nio.file.Files.deleteIfExists(path);}
  }

  @Test void clipsOwnedLowResolutionVideoWithoutChangingOriginal() throws Exception {
    var path=java.nio.file.Files.createTempFile("replica-small-", ".mp4");
    var rotated=java.nio.file.Files.createTempFile("replica-rotated-", ".mp4");
    try {
      for(String sample:new String[]{"336x596", "596x336", "596x336:rotate"}) {
        String dimensions=sample.split(":")[0];
        boolean rotate=sample.endsWith(":rotate");
        var process=new ProcessBuilder("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i",
            "color=s="+dimensions+":r=24:d=15.041667", "-c:v", "libx264", "-preset", "ultrafast", path.toString()).start();
        org.junit.jupiter.api.Assertions.assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0,process.exitValue());
        if(rotate) {
          var rotation=new ProcessBuilder("ffmpeg", "-v", "error", "-y", "-display_rotation", "90", "-i", path.toString(),
              "-c", "copy", rotated.toString()).start();
          org.junit.jupiter.api.Assertions.assertTrue(rotation.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));
          assertEquals(0,rotation.exitValue());
        }
        byte[] original=java.nio.file.Files.readAllBytes(rotate?rotated:path);
        var uploaded=mvc.perform(multipart("/api/v1/files/processing-videos")
            .file(file("small.mp4","video/mp4",original)).principal(()->"user-1")).andReturn().getResponse();
        assertEquals(201,uploaded.getStatus(),uploaded.getContentAsString());
        var source=json.readTree(uploaded.getContentAsString());
        String id=source.path("fileId").asText();
        var clipped=mvc.perform(post("/api/v1/files/"+id+"/reference-clip").principal(()->"user-1")
            .contentType("application/json").content("{\"maxDurationSeconds\":15}")).andReturn().getResponse();
        assertEquals(200,clipped.getStatus(),clipped.getContentAsString());
        var result=json.readTree(clipped.getContentAsString());
        int width=result.path("width").asInt(),height=result.path("height").asInt();
        org.junit.jupiter.api.Assertions.assertTrue((long)width*height>=407696);
        assertEquals(0,width%2); assertEquals(0,height%2);
        double originalRatio=source.path("width").asDouble()/source.path("height").asDouble();
        assertEquals(rotate?1/originalRatio:originalRatio,(double)width/height,0.005);
        org.junit.jupiter.api.Assertions.assertTrue(result.path("durationSeconds").asDouble()<=15);
        assertEquals(24,result.path("fps").asDouble(),0.001);
        assertEquals("REFERENCE_VIDEO",result.path("purpose").asText());
        org.junit.jupiter.api.Assertions.assertNotEquals(id,result.path("fileId").asText());
        org.junit.jupiter.api.Assertions.assertArrayEquals(original,objects.contents.get("objects/"+id));
        var unchanged=mvc.perform(get("/api/v1/files/"+id+"/video-content").principal(()->"user-1")).andReturn().getResponse();
        assertEquals(200,unchanged.getStatus());
        org.junit.jupiter.api.Assertions.assertArrayEquals(original,java.util.Base64.getDecoder().decode(json.readTree(unchanged.getContentAsString()).path("base64").asText()));
      }
    } finally {java.nio.file.Files.deleteIfExists(path); java.nio.file.Files.deleteIfExists(rotated);}
  }

  @Test void processingUploadPreservesLongVideoAndInternalAccessRequiresTokenAndOwner() throws Exception {
    var path=java.nio.file.Files.createTempFile("depth-upload-test-",".mp4");
    try {
      var process=new ProcessBuilder("ffmpeg","-v","error","-y","-f","lavfi","-i","color=s=320x180:r=2:d=31.5","-c:v","libx264",path.toString()).start();
      org.junit.jupiter.api.Assertions.assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue());
      byte[] bytes=java.nio.file.Files.readAllBytes(path);
      var uploaded=mvc.perform(multipart("/api/v1/files/processing-videos").file(file("video.mp4","video/mp4",bytes)).principal(()->"user-1")).andReturn().getResponse();
      assertEquals(201,uploaded.getStatus());
      var result=new ObjectMapper().readTree(uploaded.getContentAsString());
      assertEquals("PROCESSING_VIDEO",result.path("purpose").asText());
      assertEquals(31.5,result.path("durationSeconds").asDouble(),0.001);
      String id=result.path("fileId").asText();
      var controller=mvc.getDispatcherServlet().getWebApplicationContext().getBean(StorageController.class);
      String token="test-token-123456789012345678901234";
      var protectedMvc=MockMvcBuilders.standaloneSetup(controller).addFilters(new StorageMediaFilter(token)).build();
      String endpoint="/internal/v1/files/processing-videos/access-url";
      String body="{\"ownerId\":\"user-1\",\"fileId\":\""+id+"\"}";
      assertEquals(401,protectedMvc.perform(post(endpoint).contentType("application/json").content(body)).andReturn().getResponse().getStatus());
      assertEquals(200,protectedMvc.perform(post(endpoint).header("X-Service-Token",token).contentType("application/json").content(body)).andReturn().getResponse().getStatus());
      assertEquals(403,protectedMvc.perform(post(endpoint).header("X-Service-Token",token).contentType("application/json").content(body.replace("user-1","user-2"))).andReturn().getResponse().getStatus());
      assertEquals(401,protectedMvc.perform(multipart("/internal/v1/files/media-results").file(file("result.mp4","video/mp4",MP4)).param("ownerId","user-1")).andReturn().getResponse().getStatus());
      assertEquals(201,protectedMvc.perform(multipart("/internal/v1/files/media-results").file(file("result.mp4","video/mp4",MP4)).header("X-Service-Token",token).param("ownerId","user-1")).andReturn().getResponse().getStatus());
      assertEquals(400,mvc.perform(multipart("/api/v1/files/reference-media").file(file("video.mp4","video/mp4",bytes)).principal(()->"user-1")).andReturn().getResponse().getStatus());
    } finally {java.nio.file.Files.deleteIfExists(path);}
  }

  @Test void taskPreviewRequiresServiceTokenAndRealOwnerAndFile() throws Exception {
    mvc.perform(multipart("/api/v1/files/reference-images").file(file("r.png","image/png",PNG)).principal(()->"user-1"));
    // Reuse this fixture's real metadata/controller through the existing MVC filter chain.
    var context=mvc.getDispatcherServlet().getWebApplicationContext();
    var controller=context.getBean(StorageController.class);
    var protectedMvc=MockMvcBuilders.standaloneSetup(controller).addFilters(new StorageMediaFilter("test-token-123456789012345678901234")).build();
    String path="/internal/v1/files/access-url";
    String body="{\"ownerId\":\"user-1\",\"fileId\":\"file-1\"}";
    assertEquals(401,protectedMvc.perform(post(path).contentType("application/json").content(body)).andReturn().getResponse().getStatus());
    assertEquals(200,protectedMvc.perform(post(path).header("X-Service-Token","test-token-123456789012345678901234").contentType("application/json").content(body)).andReturn().getResponse().getStatus());
    assertEquals(403,protectedMvc.perform(post(path).header("X-Service-Token","test-token-123456789012345678901234").contentType("application/json").content(body.replace("user-1","user-2"))).andReturn().getResponse().getStatus());
    assertEquals(404,protectedMvc.perform(post(path).header("X-Service-Token","test-token-123456789012345678901234").contentType("application/json").content(body.replace("file-1","deleted"))).andReturn().getResponse().getStatus());
  }

  @Test void deletedObjectCannotBeCopiedAsAnApparentlyValidTaskLink() throws Exception {
    mvc.perform(multipart("/api/v1/files/reference-images").file(file("r.png","image/png",PNG)).principal(()->"user-1"));
    objects.objects.clear();
    assertEquals(502,mvc.perform(post("/internal/v1/files/access-url").principal(()->"media-service")
        .contentType("application/json").content("{\"ownerId\":\"user-1\",\"fileId\":\"file-1\"}"))
        .andReturn().getResponse().getStatus());
  }
  @Test void optionalArchiveIdentityRemainsInternalAndRejectsInvalidKeys() throws Exception {
    var controller=mvc.getDispatcherServlet().getWebApplicationContext().getBean(StorageController.class);
    String token="test-token-123456789012345678901234";
    var protectedMvc=MockMvcBuilders.standaloneSetup(controller).addFilters(new StorageMediaFilter(token)).build();
    String path="/internal/v1/files/media-results/archive-url";
    for (String key : new String[] {null, "task:RESULT:0", "", "bad/key", "x".repeat(257)}) {
      var body=new java.util.HashMap<String,String>();
      body.put("ownerId","user-1");body.put("sourceUrl","https://provider.example/result.png?sig=rotated");
      if(key!=null) body.put("archiveKey",key);
      String payload=json.writeValueAsString(body);
      assertEquals(401,protectedMvc.perform(post(path).contentType("application/json").content(payload))
          .andReturn().getResponse().getStatus());
      int expected=key==null || key.equals("task:RESULT:0") ? 201 : 400;
      assertEquals(expected,protectedMvc.perform(post(path).header("X-Service-Token",token)
          .contentType("application/json").content(payload)).andReturn().getResponse().getStatus());
    }
  }

  private static final byte[] JPEG = bytes(0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4);
  private static final byte[] PNG = bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1);
  private static final byte[] MP4 = bytes(0, 0, 0, 20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm');
  private static final Instant NOW = Instant.parse("2026-09-07T08:00:00Z");
  private final ObjectMapper json = new ObjectMapper();
  private MockMvc mvc;
  private MemoryObjectStorage objects;

  @BeforeEach
  void setUp() {
    objects = new MemoryObjectStorage();
    var metadata = new MemoryMetadataStore();
    var ids = new AtomicInteger();
    var store = new StoreFileHandler(objects, metadata,
        () -> "file-" + ids.incrementAndGet(), Clock.fixed(NOW, ZoneOffset.UTC), 1024);
    var remote = new RemoteFileSource() {
      @Override
      public RemoteFile open(URI source) {
        return new RemoteFile() {
          @Override public String mimeType() { return "image/png"; }
          @Override public long contentLength() { return PNG.length; }
          @Override public InputStream body() { return new ByteArrayInputStream(PNG); }
          @Override public void close() {}
        };
      }
    };
    mvc = MockMvcBuilders.standaloneSetup(new StorageController(
        store, new ArchiveUrlHandler(remote, store, (key, upload) -> upload.apply("archive-" + "a".repeat(64))),
        new CreateAccessUrlHandler(metadata, objects, Clock.fixed(NOW, ZoneOffset.UTC))))
        .build();
  }

  @Test
  void exposesOnlyReferenceAndAvatarPublicMultipartUploads() throws Exception {
    var reference = mvc.perform(multipart("/api/v1/files/reference-images")
        .file(file("reference.jpg", "image/jpeg", JPEG))
        .principal(() -> "user-1"))
        .andReturn().getResponse();
    assertEquals(201, reference.getStatus());
    assertEquals("REFERENCE_IMAGE", json.readTree(reference.getContentAsString()).path("purpose").textValue());

    var avatar = mvc.perform(multipart("/api/v1/files/avatars")
        .file(file("avatar.png", "image/png", PNG))
        .principal(() -> "user-1"))
        .andReturn().getResponse();
    assertEquals(201, avatar.getStatus());
    assertEquals("AVATAR", json.readTree(avatar.getContentAsString()).path("purpose").textValue());

    int videoStatus = mvc.perform(multipart("/api/v1/files/reference-images")
        .file(file("result.mp4", "video/mp4", MP4))
        .principal(() -> "user-1"))
        .andReturn().getResponse().getStatus();
    assertEquals(400, videoStatus);
    assertEquals(401, mvc.perform(multipart("/api/v1/files/avatars")
        .file(file("avatar.png", "image/png", PNG)))
        .andReturn().getResponse().getStatus());
  }

  @Test
  void signsPrivateReadsForTheOwnerForExactlyOneHour() throws Exception {
    var upload = mvc.perform(multipart("/api/v1/files/reference-images")
        .file(file("reference.jpg", "image/jpeg", JPEG))
        .principal(() -> "user-1"))
        .andReturn().getResponse();
    String fileId = json.readTree(upload.getContentAsString()).path("fileId").textValue();

    var access = mvc.perform(get("/api/v1/files/{fileId}/access-url", fileId)
        .principal(() -> "user-1"))
        .andReturn().getResponse();
    assertEquals(200, access.getStatus());
    assertEquals("https://signed.example/objects/file-1?expires=3600",
        json.readTree(access.getContentAsString()).path("url").textValue());
    assertEquals(NOW.plusSeconds(3600).getEpochSecond(),
        json.readTree(access.getContentAsString()).path("expiresAt").decimalValue().longValue());

    assertEquals(403, mvc.perform(get("/api/v1/files/{fileId}/access-url", fileId)
        .principal(() -> "user-2"))
        .andReturn().getResponse().getStatus());
  }

  @Test
  void archivesGeneratedImageAndVideoOnlyThroughInternalEndpoints() throws Exception {
    var binary = mvc.perform(multipart("/internal/v1/files/media-results")
        .file(file("result.mp4", "video/mp4", MP4))
        .param("ownerId", "user-1")
        .principal(() -> "media-service"))
        .andReturn().getResponse();
    assertEquals(201, binary.getStatus());
    assertEquals("MEDIA_RESULT", json.readTree(binary.getContentAsString()).path("purpose").textValue());

    var remote = mvc.perform(post("/internal/v1/files/media-results/archive-url")
        .contentType("application/json")
        .principal(() -> "media-service")
        .content("{\"ownerId\":\"user-1\",\"sourceUrl\":\"https://provider.example/result.png\"}"))
        .andReturn().getResponse();
    assertEquals(201, remote.getStatus());
    assertEquals("image/png", json.readTree(remote.getContentAsString()).path("mimeType").textValue());

    assertEquals(400, mvc.perform(post("/internal/v1/files/media-results/archive-url")
        .contentType("application/json")
        .principal(() -> "media-service")
        .content("{\"ownerId\":\"user-1\",\"sourceUrl\":\"data:image/png;base64,AAAA\"}"))
        .andReturn().getResponse().getStatus());

    assertEquals(401, mvc.perform(multipart("/internal/v1/files/media-results")
        .file(file("result.mp4", "video/mp4", MP4))
        .param("ownerId", "user-1"))
        .andReturn().getResponse().getStatus());
  }

  @Test void mediaCanResolveOnlyOwnedReferenceImagesWithItsToken() throws Exception {
    mvc.perform(multipart("/api/v1/files/reference-images").file(file("r.png", "image/png", PNG))
        .principal(() -> "user-1"));
    mvc.perform(multipart("/api/v1/files/avatars").file(file("a.png", "image/png", PNG))
        .principal(() -> "user-1"));
    mvc.perform(multipart("/internal/v1/files/media-results").file(file("r.png", "image/png", PNG))
        .param("ownerId", "user-1").principal(() -> "media-service"));
    var filter = new StorageMediaFilter("media-token-012345678901234567890123");
    for (String[] scenario : new String[][] {
        {"user-1", "file-1", "200"}, {"user-2", "file-1", "403"},
        {"user-1", "file-2", "403"}, {"user-1", "file-3", "403"}}) {
      var response = mvc.perform(post("/internal/v1/files/reference-images/access-url")
          .principal(() -> "media-service").contentType("application/json")
          .content(json.writeValueAsString(Map.of("ownerId", scenario[0], "fileId", scenario[1]))))
          .andReturn().getResponse();
      assertEquals(Integer.parseInt(scenario[2]), response.getStatus());
      if (response.getStatus() == 200) {
        assertEquals("https", URI.create(json.readTree(response.getContentAsString()).path("url").asText()).getScheme());
        assertEquals(NOW.plusSeconds(3600).getEpochSecond(),
            json.readTree(response.getContentAsString()).path("expiresAt").decimalValue().longValue());
      }
    }
    var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/internal/v1/files/reference-images/access-url");
    var response = new org.springframework.mock.web.MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> { throw new AssertionError("missing token admitted"); });
    assertEquals(401, response.getStatus());
    request.addHeader("X-Service-Token", "media-token-012345678901234567890123");
    filter.doFilter(request, new org.springframework.mock.web.MockHttpServletResponse(), (req, res) ->
        assertEquals("media-service", ((jakarta.servlet.http.HttpServletRequest) req).getUserPrincipal().getName()));
  }

  @Test void referenceMediaUsesRealMetadataAndRestrictsServiceAccess() throws Exception {
    byte[] wav = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(getClass().getResource("/reference-media/tone.wav").toURI()));
    var upload = mvc.perform(multipart("/api/v1/files/reference-media")
        .file(file("tone.wav", "audio/wav", wav)).principal(() -> "user-1"))
        .andReturn().getResponse();
    assertEquals(201, upload.getStatus(), upload.getContentAsString());
    var result = json.readTree(upload.getContentAsString());
    assertEquals("REFERENCE_AUDIO", result.path("purpose").asText());
    assertEquals(3.0, result.path("durationSeconds").asDouble(), 0.05);
    assertEquals(wav.length, result.path("sizeBytes").asLong());
    var controller = mvc.getDispatcherServlet().getWebApplicationContext().getBean(StorageController.class);
    var protectedMvc = MockMvcBuilders.standaloneSetup(controller)
        .addFilters(new StorageMediaFilter("test-token-123456789012345678901234")).build();
    String path = "/internal/v1/files/reference-media/access-url";
    String body = "{\"ownerId\":\"user-1\",\"fileId\":\"file-1\"}";
    assertEquals(401, protectedMvc.perform(post(path).contentType("application/json").content(body))
        .andReturn().getResponse().getStatus());
    var access = protectedMvc.perform(post(path).header("X-Service-Token", "test-token-123456789012345678901234")
        .contentType("application/json").content(body)).andReturn().getResponse();
    assertEquals(200, access.getStatus());
    assertEquals("audio/wav", json.readTree(access.getContentAsString()).path("mimeType").asText());
    assertEquals(3.0, json.readTree(access.getContentAsString()).path("durationSeconds").asDouble(), 0.05);
    assertEquals(403, protectedMvc.perform(post(path).header("X-Service-Token", "test-token-123456789012345678901234")
        .contentType("application/json").content(body.replace("user-1", "user-2")))
        .andReturn().getResponse().getStatus());
  }

  @Test void referenceMediaAcceptsRealMp4MovMp3AndRejectsFakeOrWrongMime() throws Exception {
    for (String[] fixture : new String[][] {{"clip.mp4", "video/mp4", "REFERENCE_VIDEO"},
        {"clip.mov", "video/quicktime", "REFERENCE_VIDEO"}, {"h264-aac.mp4", "video/mp4", "REFERENCE_VIDEO"},
        {"hevc-mp3.mov", "video/quicktime", "REFERENCE_VIDEO"}, {"tone.mp3", "audio/mpeg", "REFERENCE_AUDIO"}}) {
      byte[] data = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(getClass().getResource("/reference-media/" + fixture[0]).toURI()));
      var response = mvc.perform(multipart("/api/v1/files/reference-media")
          .file(file(fixture[0], fixture[1], data)).principal(() -> "user-1")).andReturn().getResponse();
      assertEquals(201, response.getStatus(), response.getContentAsString());
      var stored = json.readTree(response.getContentAsString());
      assertEquals(fixture[2], stored.path("purpose").asText());
      if (fixture[0].equals("tone.mp3")) {
        // 44 MP3 frames * 576 samples / 8000 Hz = 3.168 s. FFmpeg commit
        // 5a526fdad01054d611be715099432bc7ac94a14b subtracts 1344 padding samples: 3.0 s.
        double duration = stored.path("durationSeconds").asDouble();
        org.junit.jupiter.api.Assertions.assertTrue(duration == 3.0 || duration == 3.168,
            "tone.mp3 must retain the exact probed duration: " + duration);
      } else {
        assertEquals(3.0, stored.path("durationSeconds").asDouble(), 0.1);
      }
      if (fixture[2].equals("REFERENCE_VIDEO")) {
        assertEquals(640, stored.path("width").asInt());
        assertEquals(640, stored.path("height").asInt());
        assertEquals(30.0, stored.path("fps").asDouble(), 0.01);
      }
      assertEquals(400, mvc.perform(multipart("/api/v1/files/reference-media")
          .file(file("fake.png", "image/png", data)).principal(() -> "user-1"))
          .andReturn().getResponse().getStatus());
    }
    assertEquals(400, mvc.perform(multipart("/api/v1/files/reference-media")
        .file(file("fake.mp4", "video/mp4", MP4)).principal(() -> "user-1"))
        .andReturn().getResponse().getStatus());
  }

  @Test void legacyImageReferencesAndResultsRemainResolvableButAvatarsAreNotReferences() throws Exception {
    mvc.perform(multipart("/api/v1/files/reference-images").file(file("old.png", "image/png", PNG)).principal(() -> "user-1"));
    mvc.perform(multipart("/api/v1/files/avatars").file(file("avatar.png", "image/png", PNG)).principal(() -> "user-1"));
    mvc.perform(multipart("/internal/v1/files/media-results").file(file("result.png", "image/png", PNG))
        .param("ownerId", "user-1").principal(() -> "media-service"));
    for (int id = 1; id <= 3; id++) {
      var response = mvc.perform(post("/internal/v1/files/reference-media/access-url")
          .principal(() -> "media-service").contentType("application/json")
          .content(json.writeValueAsString(Map.of("ownerId", "user-1", "fileId", "file-" + id))))
          .andReturn().getResponse();
      assertEquals(id == 2 ? 403 : 200, response.getStatus());
      if (id == 1) org.junit.jupiter.api.Assertions.assertTrue(json.readTree(response.getContentAsString()).path("width").isNull());
    }
  }

  @Test void newImageUploadChecksDimensionsWithoutChangingLegacyImageUploads() throws Exception {
    for (int width : new int[] {299, 600, 6001}) {
      var bytes = new java.io.ByteArrayOutputStream();
      javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(width, 600, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", bytes);
      var response = mvc.perform(multipart("/api/v1/files/reference-media")
          .file(file("reference.png", "image/png", bytes.toByteArray())).principal(() -> "user-1"))
          .andReturn().getResponse();
      assertEquals(width == 600 ? 201 : 400, response.getStatus(), response.getContentAsString());
      if (width == 600) assertEquals(600, json.readTree(response.getContentAsString()).path("width").asInt());
    }
  }

  @Test void rejectsOutOfRangeDurationsPixelsAspectRatioAndFrameRatesBeforeUpload() throws Exception {
    for (String fixture : new String[] {"short.wav", "long.wav", "short.mp4", "long.mp4", "lowfps.mp4", "highfps.mp4",
        "small.mp4", "large.mp4", "wide.mp4"}) {
      byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(getClass().getResource("/reference-media/" + fixture).toURI()));
      var response = mvc.perform(multipart("/api/v1/files/reference-media")
          .file(file(fixture, fixture.endsWith(".wav") ? "audio/wav" : "video/mp4", bytes)).principal(() -> "user-1"))
          .andReturn().getResponse();
      assertEquals(400, response.getStatus(), fixture + ": " + response.getContentAsString());
    }
    org.junit.jupiter.api.Assertions.assertTrue(objects.objects.isEmpty());
  }

  @Test void omniRejectsUninspectedLegacyImagesWhileFirstLastRemainsCompatible() throws Exception {
    mvc.perform(multipart("/api/v1/files/reference-images").file(file("old.png", "image/png", PNG)).principal(() -> "user-1"));
    for (boolean omni : new boolean[] {false, true}) {
      var response = mvc.perform(post("/internal/v1/files/reference-media/access-url")
          .principal(() -> "media-service").contentType("application/json")
          .content(json.writeValueAsString(Map.of("ownerId", "user-1", "fileId", "file-1", "omni", omni))))
          .andReturn().getResponse();
      assertEquals(omni ? 400 : 200, response.getStatus());
    }
    var bytes = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(600, 600, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", bytes);
    mvc.perform(multipart("/api/v1/files/reference-media")
        .file(file("valid.png", "image/png", bytes.toByteArray())).principal(() -> "user-1"));
    assertEquals(200, mvc.perform(post("/internal/v1/files/reference-media/access-url")
        .principal(() -> "media-service").contentType("application/json")
        .content(json.writeValueAsString(Map.of("ownerId", "user-1", "fileId", "file-2", "omni", true))))
        .andReturn().getResponse().getStatus());
  }

  @Test void referenceVideoRejectsUnsupportedVideoAndEmbeddedAudioCodecs() throws Exception {
    for (String fixture : new String[] {"mpeg4.mp4", "unsupported-audio.mp4", "unsupported-second-audio.mp4", "unsupported-second-video.mp4"}) {
      byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(getClass().getResource("/reference-media/" + fixture).toURI()));
      var response = mvc.perform(multipart("/api/v1/files/reference-media")
          .file(file(fixture, "video/mp4", bytes)).principal(() -> "user-1")).andReturn().getResponse();
      assertEquals(400, response.getStatus(), fixture + ": " + response.getContentAsString());
    }
    org.junit.jupiter.api.Assertions.assertTrue(objects.objects.isEmpty());
  }

  private static MockMultipartFile file(String filename, String mimeType, byte[] body) {
    return new MockMultipartFile("file", filename, mimeType, body);
  }

  private static byte[] bytes(int... values) {
    byte[] result = new byte[values.length];
    for (int index = 0; index < values.length; index++) result[index] = (byte) values[index];
    return result;
  }

  private static final class MemoryObjectStorage implements PrivateObjectStorage {
    private final Map<String, StoredObject> objects = new HashMap<>();
    private final Map<String, byte[]> contents = new HashMap<>();

    @Override
    public String putPrivate(String objectKey, String mimeType, long contentLength, InputStream body) {
      try {
        byte[] content = body.readAllBytes();
        contents.put(objectKey, content);
        long actual = content.length;
        objects.put(objectKey, new StoredObject(objectKey, mimeType, actual, "etag"));
        return "etag";
      } catch (IOException failed) {
        throw new FileStorageException("read failed", failed);
      }
    }

    @Override public InputStream open(String objectKey) {
      if (!objects.containsKey(objectKey)) throw new FileStorageException("missing object");
      return new ByteArrayInputStream(contents.get(objectKey));
    }
    @Override public StoredObject head(String objectKey) { return objects.get(objectKey); }
    @Override public void delete(String objectKey) { objects.remove(objectKey); }
    @Override public String signGet(String objectKey, long expiresSeconds) {
      return "https://signed.example/" + objectKey + "?expires=" + expiresSeconds;
    }
  }

  private static final class MemoryMetadataStore implements FileMetadataStore {
    private final Map<String, StoredFile> files = new HashMap<>();
    @Override public void save(StoredFile file) { files.put(file.fileId(), file); }
    @Override public Optional<StoredFile> findById(String fileId) {
      return Optional.ofNullable(files.get(fileId));
    }
  }
}
