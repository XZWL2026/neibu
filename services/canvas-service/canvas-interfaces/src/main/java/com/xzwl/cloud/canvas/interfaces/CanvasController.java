package com.xzwl.cloud.canvas.interfaces;

import com.xzwl.cloud.canvas.application.*;
import com.xzwl.cloud.canvas.domain.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/canvases")
public final class CanvasController {
  private final OwnerCanvasStores stores;
  private final Clock clock;
  public CanvasController(OwnerCanvasStores stores, Clock clock) { this.stores = stores; this.clock = clock; }
  public record CreateRequest(String name) {}
  public record SaveRequest(long expectedVersion, CanvasContent content, String name) {}
  public record CopyRequest(String name) {}
  public record MediaReferenceRequest(long expectedVersion, String nodeId, MediaResultReference.Kind kind, String publicId) {}
  public record CanvasResponse(String id, String ownerId, String name, CanvasContent content, long version,
      Instant createdAt, Instant updatedAt, boolean deleted) {
    static CanvasResponse from(Canvas c) {
      return new CanvasResponse(c.id(), c.ownerId(), c.name(), c.content(), c.version(), c.createdAt(), c.updatedAt(), c.deleted());
    }
  }
  public record CanvasSummary(String id, String name, Instant updatedAt, List<CanvasContent.Node> previewNodes) {}
  public record CanvasListResponse(List<CanvasSummary> items, boolean hasMore) {}

  @GetMapping
  public CanvasListResponse list(@RequestParam(value="offset", defaultValue="0") int offset,
      @RequestParam(value="limit", defaultValue="12") int limit, HttpServletRequest request) {
    if (offset < 0 || limit < 1 || limit > 24) throw new IllegalArgumentException("Invalid pagination");
    var page = store(request).findPage(offset, limit + 1);
    boolean hasMore = page.size() > limit;
    var items = page.stream().limit(limit)
        .map(canvas -> new CanvasSummary(canvas.id(), canvas.name(), canvas.updatedAt(), preview(canvas.content())))
        .toList();
    return new CanvasListResponse(items, hasMore);
  }
  @PostMapping @ResponseStatus(HttpStatus.CREATED)
  public CanvasResponse create(@RequestBody CreateRequest body, HttpServletRequest request) {
    String owner = owner(request);
    return CanvasResponse.from(new CreateCanvasHandler(stores.forOwner(owner)).handle(
        new CreateCanvasCommand(UUID.randomUUID().toString(), owner, CanvasInputPolicy.name(body.name()), now())));
  }
  @GetMapping("/{canvasId}")
  public CanvasResponse read(@PathVariable("canvasId") String id, HttpServletRequest request) {
    return CanvasResponse.from(owned(store(request), id));
  }
  @PutMapping("/{canvasId}")
  public CanvasResponse save(@PathVariable("canvasId") String id, @RequestBody SaveRequest body, HttpServletRequest request) {
    var store = store(request); owned(store, id); version(body.expectedVersion());
    if (body.content() == null) throw new IllegalArgumentException("content is required");
    return CanvasResponse.from(new SaveCanvasHandler(store).handle(new SaveCanvasCommand(id, body.expectedVersion(), body.content(), now(), body.name())));
  }
  @PostMapping("/{canvasId}/copies") @ResponseStatus(HttpStatus.CREATED)
  public CanvasResponse copy(@PathVariable("canvasId") String id, @RequestBody CopyRequest body, HttpServletRequest request) {
    var store = store(request); owned(store, id);
    return CanvasResponse.from(new CopyCanvasHandler(store).handle(new CopyCanvasCommand(id, UUID.randomUUID().toString(), CanvasInputPolicy.name(body.name()), now())));
  }
  @DeleteMapping("/{canvasId}")
  public CanvasResponse delete(@PathVariable("canvasId") String id, @RequestParam("expectedVersion") long expectedVersion, HttpServletRequest request) {
    var store = store(request); owned(store, id); version(expectedVersion);
    return CanvasResponse.from(new DeleteCanvasHandler(store).handle(new DeleteCanvasCommand(id, expectedVersion, now())));
  }
  @PostMapping("/{canvasId}/media-references")
  public CanvasResponse addMediaReference(@PathVariable("canvasId") String id, @RequestBody MediaReferenceRequest body, HttpServletRequest request) {
    var store = store(request); owned(store, id); version(body.expectedVersion());
    CanvasInputPolicy.identifier(body.nodeId()); CanvasInputPolicy.reference(body.publicId());
    if (body.kind() == null) throw new IllegalArgumentException("kind is required");
    return CanvasResponse.from(new AddMediaReferenceHandler(store).handle(new AddMediaReferenceCommand(id, body.expectedVersion(), body.nodeId(), new MediaResultReference(body.kind(), body.publicId()), now())));
  }
  private CanvasStore store(HttpServletRequest request) { return stores.forOwner(owner(request)); }
  private static String owner(HttpServletRequest request) {
    if (request.getUserPrincipal() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    // Canvas is a user workspace; administrator identities must not alias user IDs.
    if (!request.isUserInRole("USER")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return request.getUserPrincipal().getName();
  }
  private static Canvas owned(CanvasStore store, String id) {
    return store.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }
  private static void version(long version) {
    if (version < 1 || version == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid expectedVersion");
  }
  private static List<CanvasContent.Node> preview(CanvasContent content) {
    return content.nodes().stream().limit(8).map(node -> {
      var source = node.properties();
      var properties = new LinkedHashMap<String, String>();
      for (String key : List.of("x", "y")) if (source.containsKey(key)) properties.put(key, source.get(key));
      for (String key : List.of("text", "alt")) if (source.containsKey(key)) properties.put(key, crop(source.get(key)));
      String publicId = source.get("publicId");
      if (publicId == null) for (String legacy : List.of("fileId", "url", "src")) {
        if (source.containsKey(legacy)) { publicId = source.get(legacy); break; }
      }
      if (publicId != null) properties.put("publicId", publicId);
      return new CanvasContent.Node(node.id(), node.kind(), properties);
    }).toList();
  }
  private static String crop(String value) {
    int end = value.offsetByCodePoints(0, Math.min(160, value.codePointCount(0, value.length())));
    return value.substring(0, end);
  }
  private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
