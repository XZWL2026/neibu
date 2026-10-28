package com.xzwl.cloud.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Offline contract extraction from compiled Spring annotations and Jackson types; no service startup. */
class ReleaseContractTest {
  static final Path ROOT = Path.of("../..").toAbsolutePath().normalize();
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().addMixIn(
      com.xzwl.cloud.canvas.domain.CanvasContent.class,
      com.xzwl.cloud.canvas.interfaces.CanvasJsonConfiguration.ContentMixin.class);
  static final List<String> SERVICES = List.of("iam", "user", "access", "billing", "payment",
      "routing", "storage", "media", "promotion", "operations", "canvas");
  final ObjectNode schemas = JSON.createObjectNode();
  final ObjectNode errorResponses = JSON.createObjectNode();
  record Endpoint(String service, String path, String verb, Method method, Path source, boolean local) {}

  static List<Endpoint> endpoints() throws Exception {
    List<Endpoint> result = new ArrayList<>();
    for (String service : SERVICES) {
      Path javaRoot = ROOT.resolve("services/" + service + "-service/" + service + "-interfaces/src/main/java");
      try (var files = Files.walk(javaRoot)) {
        for (Path source : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
          if (!java.util.regex.Pattern.compile("@RestController\\b").matcher(Files.readString(source)).find()) continue;
          String name = javaRoot.relativize(source).toString().replace('\\', '.').replace('/', '.').replace(".java", "");
          Class<?> controller = Class.forName(name);
          if (!controller.isAnnotationPresent(RestController.class)) continue;
          var parent = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
          String[] prefixes = parent == null || parent.path().length == 0 ? new String[]{""} : parent.path();
          for (Method method : controller.getDeclaredMethods()) {
            var mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping == null) continue;
            assertThat(mapping.method()).as(method.toString()).hasSize(1);
            for (String prefix : prefixes) {
              for (String suffix : mapping.path().length == 0 ? new String[]{""} : mapping.path()) {
                result.add(new Endpoint(service, prefix + suffix, mapping.method()[0].name(), method,
                    ROOT.relativize(source), controller.isAnnotationPresent(org.springframework.context.annotation.Profile.class)));
              }
            }
          }
        }
      }
    }
    // Logout is provided by Spring Security, not a Controller.
    result.add(new Endpoint("iam", "/api/v1/auth/logout", "POST", null,
        Path.of("services/iam-service/iam-bootstrap/src/main/java/com/xzwl/cloud/iam/IamSecurityConfiguration.java"), false));
    return result.stream().sorted(Comparator.comparing(Endpoint::service).thenComparing(Endpoint::path)
        .thenComparing(Endpoint::verb)).toList();
  }
  static boolean isPublic(Endpoint e) {
    return !e.local && !e.path.startsWith("/internal/") && !e.path.startsWith("/api/v1/internal/");
  }

  @Test void contractAndRoutesMatchCompiledControllers() throws Exception {
    var endpoints = endpoints();
    assertThat(endpoints.stream().map(Endpoint::service).distinct().toList()).containsExactlyInAnyOrderElementsOf(SERVICES);
    ObjectNode document = JSON.createObjectNode();
    document.put("openapi", "3.1.0");
    document.set("info", JSON.valueToTree(Map.of("title", "XZWL new-system public API", "version", "1.0.0",
        "description", "New-system Controller snapshot. No legacy compatibility. Relative URLs target Gateway. See docs/release-gateway-contract.md for browser login, role rules and deployment requirements.")));
    document.set("servers", JSON.valueToTree(List.of(Map.of("url", "/"))));
    var paths = document.putObject("paths");
    var routeList = JSON.createArrayNode();
    StringBuilder inventory = new StringBuilder("# Release Controller inventory\n\nGenerated from compiled Controller mappings. Local-only IAM login is never routed.\n\n| Service | Visibility | Method | Path | Source |\n| --- | --- | --- | --- | --- |\n");
    for (Endpoint e : endpoints) {
      inventory.append("| ").append(e.service).append(" | ").append(e.local ? "local only" : isPublic(e) ? "public" : "internal")
          .append(" | ").append(e.verb).append(" | `").append(e.path).append("` | `").append(e.source.toString().replace('\\', '/')).append("` |\n");
      if (!isPublic(e)) continue;
      routeList.addObject().put("service", e.service).put("method", e.verb).put("path", e.path);
      var path = paths.withObjectProperty(e.path);
      assertThat(path.has(e.verb.toLowerCase(Locale.ROOT))).as(e.path).isFalse();
      path.set(e.verb.toLowerCase(Locale.ROOT), operation(e));
    }
    ((ObjectNode)paths.path("/api/v1/auth/phone/login").path("post")).put("description", "Temporary phone-only login, enabled only by IAM_PHONE_ONLY_LOGIN_ENABLED (default false). Does not verify phone ownership. Creates or reuses a normalized enabled USER account, establishing the normal session with CSRF and session rotation. Disabled mode returns 403 PHONE_ONLY_LOGIN_DISABLED before resolving users. Existing OTP and admin password login remain unchanged.");
    ((ObjectNode)paths.path("/api/v1/media/tasks").path("get")).put("description", "Lists owned non-deleted task histories ordered by id descending. Optional commerceOnly=true selects commerce- task history only. Otherwise excludeCanvas=true selects independent history by omitting canvas-, suite-, model- and commerce- idempotency-key prefixes. Filtering occurs before cursor pagination. Both omitted or false includes all owned tasks. Individual task and result access is unchanged.");
    ((ObjectNode)paths.path("/api/v1/media/tasks/{taskId}").path("delete")).put("description", "Idempotently removes an owned terminal independent GENERATION task from history. Rejects running, canvas, suite and depth tasks. Task records, archived files and billing remain available to existing references and internal processing.");
    ((ObjectNode)paths.path("/api/v1/media/tasks/{taskId}/regenerations").path("post")).put("description", "Regenerates an owned terminal independent GENERATION task (excluding canvas and suite children) using its persisted prompt, references and settings with current admission validation and normal charging. Requires a fresh Idempotency-Key retaining the source commerce- prefix for commerce tasks (and no commerce- prefix for ordinary tasks). Atomically creates a new task and hides the source history on acceptance; a rejected admission retains the source. Replaying the same source and key returns the same new task even after source history is hidden. Competing different keys for the same source conflict. No body is accepted by the client contract.");
    for (String path : List.of("/api/v1/admin/billing/users/{ownerId}/media-prices", "/api/v1/admin/billing/users/{ownerId}/media-prices/audit")) {
      ((ObjectNode)paths.path(path).path("get")).put("description", "Primary ADMIN only. Per-user pricing for the server-verified PERSONAL, ENTERPRISE or API account. Overrides inherit enabled defaults of that account type; other owners and account types are isolated. Audit lists the latest 100 changes for this owner and current account type.");
    }
    ((ObjectNode)paths.path("/api/v1/admin/billing/users/{ownerId}/media-prices").path("put")).put("description", "Primary ADMIN only. Replaces this user's overrides using expectedVersion and idempotent requestId. Account type is resolved from the verified owner key, never from a client selector. Only enabled default dimensions and four-place nonnegative yuan prices are accepted; omitted dimensions inherit defaults and an empty list restores defaults. Identical retries replay, conflicting facts or stale versions return 409. Legacy API customer endpoints remain compatible.");
    ((ObjectNode)paths.path("/api/v1/admin/routing/models/{modelId}").path("put")).put("description", "Primary ADMIN only. Saves model enablement and structurally validated capability configuration. The UI selects a nonempty subset of the adapted catalog; new tasks additionally validate actual adapter capabilities before charging. Disabled models are unavailable to new routes and the user catalog; existing route snapshots continue. Nullable timeoutSeconds (1–180) and retryCount (0–3) inherit the provider connection. Effective policy is frozen before charging; retryCount applies only to safe result GET requests within one total timeout budget, never generation POST.");
    ((ObjectNode)paths.path("/api/v1/admin/media/provider-logs").path("get")).put("description", "Primary ADMIN only. Read-only redacted provider diagnostic summaries, newest first. Exact taskId and phase filters; from inclusive/to exclusive epoch milliseconds. Default range is 24 hours or all retained records for an exact task. Without taskId the maximum range is 31 days. offset 0–10000, limit 1–50 (default 20). Responses are no-store; no retry or deletion operation.");
    ((ObjectNode)paths.path("/api/v1/admin/media/provider-logs/{id}").path("get")).put("description", "Primary ADMIN only. Reads one previously redacted diagnostic record by diagnostic ID; detailJson contains the stored JSON evidence. Never returns credentials from provider configuration or replays requests. Response is no-store.");
    ((ObjectNode)paths.path("/api/v1/admin/media/provider-logs/tasks").path("get")).put("description", "Primary ADMIN only. Groups matching diagnostics by task before pagination. One item per task with matching recordCount and latest matching entry; total counts tasks. Same filters and bounds as the flat diagnostic list. Use the flat list with exact taskId to browse all retained records for a selected task. Response is no-store.");
    ((ObjectNode)paths.path("/api/v1/admin/media/ark-connection").path("put")).put("description", "Primary ADMIN only. Versioned per-provider connection settings. timeoutSeconds is 1–180 and retryCount is 0–3; omitted values retain the existing policy. Empty API Key retains the current secret subject to existing origin checks. Model overrides take precedence for new tasks; accepted tasks retain their frozen policy.");
    ((ObjectNode)paths.path("/api/v1/admin/media/mps-configuration").path("put")).put("description", "Primary ADMIN only. expectedVersion protects the global connection, enhancementTemplates and bitrateProfiles aggregate. Omitted aggregates retain the stored policy. Explicit aggregates replace it; enabled templates are unique per output resolution and must accept its fixed source resolution. Referenced bitrate profiles cannot be deleted. A profile sets terminal transcode target bitrate (128–100000 kbps); it does not represent measured output bitrate. Accepted tasks retain their frozen execution plan.");
    ((ObjectNode)paths.path("/api/v1/admin/media/mps-configuration/test").path("post")).put("description", "Primary ADMIN only. Checks a saved template at expectedVersion and aspectRatio using read-only Tencent Describe calls within one shared preflight deadline. Does not create a media job, charge points or call ProcessMedia. Returns configuration validation, dimensions and configured target bitrate, not a rendered-output quality or measured-bitrate test. A stale version returns 409.");
    var components = document.putObject("components");
    components.set("schemas", schemas);
    // Preserve the existing masked-key contract when extracting new Controller endpoints.
    var maskedKey=(ObjectNode)schemas.path("response_access.interfaces.http.AccessKeyController_View").path("properties").path("maskedKey");
    maskedKey.put("description","Display-only value derived on the server from the real bound platform secret: retain the sk prefix and replace the last four characters with ****. Null when unavailable or invalid. Never return the full secret here; explicit secret retrieval remains separately authorized.");
    ((ObjectNode)maskedKey.path("anyOf").get(0)).put("pattern","^sk-[A-Za-z0-9_-]+\\*{4}$");
    ((ObjectNode)paths.path("/api/v1/admin/admins").path("post")).put("description", "Primary ADMIN creates a password credential. API_USER or PROMOTER requires an immutable boundUserId for an enabled existing business user with current API/promoter eligibility. Binding is not accepted from session or role changes. API keys are never login passwords.");
    ((ObjectNode)paths.path("/api/v1/auth/admin/login").path("post")).put("description", "Password login for ADMIN, NORMAL_ADMIN, API_USER and PROMOTER with Cookie rotation and CSRF. Portal sessions expose the verified bound business owner as user.id; credential identity and linked-user authVersion are revalidated on use.");
    ((ObjectNode)paths.path("/api/v1/auth/admin/password").path("post")).put("description", "All four password roles may change only their own credential; success invalidates previous sessions. An incorrect current password is 400 INVALID_CREDENTIALS, preserving the authenticated session for retry.");
    for (String path : List.of("/api/v1/billing/portal/consumptions", "/api/v1/billing/portal/summary")) {
      ((ObjectNode)paths.path(path).path("get")).put("description", "API_USER only, owner derived from session. Decimal money/point strings preserve four places. totalCalls counts posted CONSUME tasks, not attempted requests or success rate; totalRechargeAmount4 sums all credited CNY records. Summary from is inclusive and to exclusive (defaults to now); consumption rows and credited recharges are aggregated within that interval, refunds are evaluated as of to. Browser local-day boundaries provide today consumption without counting only a page. Missing historic cash facts remain null.");
    }
    ((ObjectNode)paths.path("/api/v1/billing/model-prices").path("get")).put("description", "Authenticated USER only. Read-only catalog of currently enabled PERSONAL and ENTERPRISE model prices; API and disabled rows are excluded. Prices are four-place yuan per IMAGE or SECOND. Legacy points prices are converted using the returned current pointsPerYuan4 rate, rounding down to four places. This display does not change charging or recharge identity binding.");
    for (String path : List.of("/api/v1/promotions/portal/overview", "/api/v1/promotions/portal/downlines", "/api/v1/promotions/portal/recharges", "/api/v1/promotions/portal/consumptions", "/api/v1/promotions/portal/withdrawals", "/api/v1/promotions/portal/payout-accounts")) {
      ((ObjectNode)paths.path(path).path("get")).put("description", "Enabled PROMOTER only; server derives owner and current referral/funding scope. Returns masked identifiers/contact/account details; no prompts, media or key secrets. Overview/recharge totals describe attributed promotion funding, while downline recharge totals describe that downline's credited billing history. Unmatched historic source facts remain null.");
    }
    ((ObjectNode)paths.path("/api/v1/promotions/portal/overview").path("get")).put("description", "Enabled PROMOTER only. levelOneCount/levelTwoCount count latest current bindings. releasedCommissionAmount4 is all-owner released commission net of released refund reversals; totalCommissionAmount4 is all-owner frozen commission net of all recharge refund clawbacks. These exact decimal totals preserve financial ownership after referral changes and exclude withdrawal transfers. paidAmount4/settledPaidAmount4/refundedPaidAmount4 remain currently attributed funding totals.");
    ((ObjectNode)paths.path("/api/v1/promotions/portal/withdrawals").path("get")).put("description", "Enabled PROMOTER own masked withdrawals only. from is inclusive and to exclusive on requestedAt, applied before pagination and total. Omitted bounds mean full history. Monetary values are exact four-place strings.");
    ((ObjectNode)paths.path("/api/v1/promotions/portal/withdrawals").path("post")).put("description", "Enabled PROMOTER requests at least 100 CNY (amount is a decimal string with up to 34 integer digits (DECIMAL(38,4) storage) and at most two fractional digits; returned commission/withdrawal money uses exact four-place strings) from available commission using an ACTIVE owned payout account. Atomic balance reservation and immutable complete payout snapshot. Retry the same commandId/withdrawalId/body after uncertain outcomes; server timestamp remains the first accepted timestamp. Does not send a payout.");
    ((ObjectNode)paths.path("/api/v1/promotions/portal/payout-accounts/{payoutAccountId}").path("put")).put("description", "Enabled PROMOTER saves own payout data using stable commandId and expectedVersion (0 for new). Retries with identical caller data replay; conflicting body/version is rejected. BANK_CARD requires bankName; other supported types are WECHAT and ALIPAY. Responses remain masked and existing DISABLED accounts remain disabled. An omitted/null qrCodeReference preserves the stored reference when editing the same payout type; a new account has none. No QR file upload is implied by a stored reference.");
    components.set("responses", errorResponses);
    schemas.set("CodeError", object(Map.of("code", JSON.valueToTree(Map.of("type", "string"))), true));
    schemas.set("MessageError", object(Map.of("message", JSON.valueToTree(Map.of("type", "string"))), true));
    schemas.set("ServiceError", object(Map.of("error", JSON.valueToTree(Map.of("type", "string"))), true));
    schemas.set("ErrorEnvelope", object(Map.of("code", JSON.valueToTree(Map.of("type", "string")),
        "message", JSON.valueToTree(Map.of("type", "string")), "requestId", JSON.valueToTree(Map.of("type", "string")),
        "details", JSON.valueToTree(Map.of("type", "object", "additionalProperties", true))), true));
    components.set("securitySchemes", JSON.valueToTree(Map.of("sessionCookie", Map.of("type", "apiKey", "in", "cookie", "name", "XZWL_SESSION"),
        "csrfToken", Map.of("type", "apiKey", "in", "header", "name", "X-CSRF-TOKEN"),
        "platformKey", Map.of("type", "http", "scheme", "bearer"))));
    assertThat(paths.has("/api/v1/media/generation-options")).isTrue();
    assertThat(paths.has("/api/v1/media/quotes")).isTrue();
    assertThat(routeList.findValuesAsText("path")).contains("/api/v1/media/generation-options", "/api/v1/media/quotes");
    assertThat(schemas.path("request_media.domain.task.TaskRequest").path("properties").has("aspectRatio")).isTrue();
    assertThat(schemas.path("response_media.interfaces.http.MediaTaskController_TaskResponse").path("properties").has("aspectRatio")).isTrue();
    assertThat(paths.path("/api/v1/media/quotes").path("post").path("responses").has("422")).isTrue();
    assertThat(paths.path("/api/v1/media/tasks").path("post").path("responses").has("422")).isTrue();
    for (String internal : List.of("/internal/v1/billing/media-quotes", "/internal/v1/billing/media-prices",
        "/internal/v1/models", "/internal/v1/files/reference-images/access-url", "/internal/v1/files/reference-media/access-url")) {
      assertThat(paths.has(internal)).isFalse();
      assertThat(routeList.findValuesAsText("path")).doesNotContain(internal);
    }
    // Controller guards and its preview projection constrain this read-only list beyond Java's wire types.
    var canvasList = (ObjectNode) paths.path("/api/v1/canvases").path("get");
    canvasList.put("description", "Lists the authenticated USER's non-deleted canvases ordered by updatedAt descending, then id descending. Pagination is offset-based; refresh to reflect concurrent updates.");
    for (var parameter : canvasList.path("parameters")) {
      var schema = (ObjectNode) parameter.path("schema");
      if (parameter.path("name").asText().equals("offset")) schema.put("minimum", 0);
      else if (parameter.path("name").asText().equals("limit")) schema.put("minimum", 1).put("maximum", 24);
    }
    var previews = (ObjectNode) schemas.path("response_canvas.interfaces.CanvasController_CanvasSummary")
        .path("properties").path("previewNodes");
    previews.put("maxItems", 8);
    var previewNode = schemas.path("response_canvas.domain.CanvasContent_Node").deepCopy();
    var previewProperties = ((ObjectNode) previewNode.path("properties")).putObject("properties");
    previewProperties.put("type", "object").put("additionalProperties", false);
    var previewFields = previewProperties.putObject("properties");
    for (String field : List.of("x", "y", "text", "alt", "publicId")) {
      var fieldSchema = previewFields.putObject(field).put("type", "string");
      if (field.equals("text") || field.equals("alt")) fieldSchema.put("maxLength", 160);
    }
    previews.set("items", previewNode);
    // Adapter input semantics cannot be inferred from Java scalar types.
    ((ObjectNode) schemas.path("response_media.application.catalog.GenerationCatalogService_Option").path("properties").path("durationStepSeconds"))
        .put("description","Supported integer-second step; 0 for images.");
    ((ObjectNode) schemas.path("response_media.application.catalog.GenerationCatalogService_Option").path("properties").path("maxReferenceImages"))
        .put("description","VIDEO ordered image references supported by the implemented adapter: 0 text only, 1 first frame, 2 first and last frame. Omni capabilities are separately declared by omniReference; IMAGE uses its image limit.");
    ((ObjectNode) schemas.path("response_media.application.catalog.GenerationCatalogService_Option").path("properties").path("referenceFollowsFirstFrame"))
        .put("description","In FIRST_LAST_FRAME mode this provider follows the first image ratio. OMNI uses the selected aspect ratio.");
    ((ObjectNode) schemas.path("request_media.interfaces.http.MediaGenerationController_QuoteRequest").path("properties").path("references"))
        .put("description","Same ordered typed references and referenceMode as task submission. Missing mode retains legacy ordered image frames; OMNI requires owned stored file IDs, validates canonical media types and durations before pricing, and uses reference_image/reference_video/reference_audio provider roles.");
    ((ObjectNode) errorResponses.path("mediaError"))
        .put("description", "422 may identify UNSUPPORTED_GENERATION_SELECTION, UNSUPPORTED_OMNI_REFERENCE, REFERENCE_METADATA_REQUIRED (reupload legacy media), REFERENCE_TYPE_MISMATCH, INVALID_REFERENCE_TYPE, or REFERENCE_REJECTED.");
    for (String name : List.of("request_media.domain.task.TaskRequest", "request_media.interfaces.http.MediaGenerationController_QuoteRequest")) {
      ((ObjectNode) schemas.path(name).path("properties").path("referenceMode"))
          .put("description", "Defaults to FIRST_LAST_FRAME for old clients. OMNI is explicit and must be supported by the selected model.");
    }
    ((ObjectNode) schemas.path("request_media.domain.task.MediaReference").path("properties").path("durationSeconds"))
        .put("description", "Client display hint only. Storage-owned media metadata replaces this value during quote and provider validation.");
    for (String path : List.of("/api/v1/payments/recharge-options", "/api/v1/admin/payments/recharge-options")) {
      ((ObjectNode) paths.path(path).path("get")).put("description", "Server-owned CNY recharge rules for the authenticated owner or verified admin target userId. firstRecharge remains true until confirmed funds; accountType is immutable after first confirmation. Presets and minimumAmount4 are yuan, pointsPerYuan4 is the current conversion rate. No product maximum; payment channel and exact-cent precision limits still apply.");
    }
    ((ObjectNode) paths.path("/api/v1/admin/payments/recharges").path("post")).put("description", "ADMIN or NORMAL_ADMIN submits a PERSONAL/ENTERPRISE/API manual CNY recharge for an existing enabled userId. Creates REVIEW_PENDING with immutable submitter and remark; no identity lock, Key or funds before confirmation. Only a primary administrator may assign API identity; normal administrators may recharge existing API accounts.");
    ((ObjectNode) paths.path("/api/v1/admin/payments/recharges/{id}/confirm").path("post")).put("description", "ADMIN or NORMAL_ADMIN confirms verified manual funds. Serializes identity/first-recharge validation and schedules the same idempotent Key/credit fulfillment. Repeated confirmation is idempotent. Never creates a provider transaction or invokes provider payment/refund.");
    ((ObjectNode) paths.path("/api/v1/payments/recharges").path("get")).put("description", "Latest 100 owner orders including unpaid, manual and refunded orders, descending createdAt/id. amount4 is CNY yuan; expectedPoints4 uses the stored rateSnapshot4. PAID with fulfilled=false is still awaiting credit; only fulfilled=true proves completion.");
    ((ObjectNode) paths.path("/api/v1/payments/recharges").path("post")).put("description", "Create or replay an idempotent provider CNY order under server account/first-recharge rules. Creation and QR checkout never bind identity. Confirmed conflicting identity or a concurrent repeat first-recharge amount enters the existing refund workflow. PAYMENT_CHANNEL_AMOUNT_LIMIT means the real provider single-order limit was exceeded; no automatic splitting.");
    ((ObjectNode) paths.path("/api/v1/files/{fileId}/match").path("post")).put("description", "Read-only comparison of a legacy local image fingerprint against an owned original reference image or image result. Authenticated owner only. SHA-256 is 64 lowercase hexadecimal characters; sizeBytes is 1..20971520. Size mismatch returns matches=false without reading the object. Equal sizes compare the complete private object's SHA-256 and verify its length. No upload, object write, approval creation or billing. Other owners/purposes return 403, missing file 404, invalid fingerprint 400 and unreadable/corrupted object 502.");
    ((ObjectNode) paths.path("/api/v1/files/{fileId}/video-content").path("get")).put("description", "Read the authenticated owner's REFERENCE_VIDEO, PROCESSING_VIDEO or video MEDIA_RESULT as mimeType and base64 for browser frame extraction without storage CORS. Private no-store response, maximum 20971520 bytes, exact stored length. Other owners or purposes return 403, missing file 404, oversized file 400 and corrupted object 502. No URL input or billing.");
    ((ObjectNode) paths.path("/api/v1/files/{fileId}/image-content").path("get")).put("description", "Read the authenticated owner's REFERENCE_IMAGE or image MEDIA_RESULT as mimeType and base64. Private no-store response, maximum 20971520 bytes; the complete stored length must match metadata. Other owners or purposes return 403, missing file 404, oversized image 400 and unreadable or corrupted object 502. No URL input or billing.");
    var fingerprintRequest = (ObjectNode) schemas.path("request_storage.interfaces.http.StorageController_FileMatchRequest");
    fingerprintRequest.set("required", JSON.valueToTree(List.of("sizeBytes", "sha256")));
    var fingerprint = fingerprintRequest.path("properties");
    ((ObjectNode) fingerprint.path("sizeBytes")).put("minimum", 1).put("maximum", 20971520);
    ((ObjectNode) fingerprint.path("sha256")).remove("anyOf");
    ((ObjectNode) fingerprint.path("sha256")).put("type", "string").put("pattern", "^[0-9a-f]{64}$");
    portraitSemantics(paths);
    var identities=(ObjectNode)paths.path("/api/v1/admin/payments/account-identities").path("get");
    identities.put("description","ADMIN or NORMAL_ADMIN reads account identities for 1 to 100 unique userIds, in request order. Settled Payment identity takes precedence; unset identities use the existing Access identity, including disabled keys. Unknown/unset identities return null. Read-only: no account creation, recharge rules or conversion-rate lookup. Pending account changes return 409; dependency failure rejects the batch.");
    var userIds=(ObjectNode)identities.path("parameters").get(0);
    userIds.put("style","form").put("explode",false);
    ((ObjectNode)userIds.path("schema")).put("minItems",1).put("maxItems",100).put("uniqueItems",true);
    ((ObjectNode) paths.path("/api/v1/files/{fileId}/reference-clip").path("post")).put("description",
        "Creates a separate REFERENCE_VIDEO from the start of an owned REFERENCE_VIDEO, PROCESSING_VIDEO or video MEDIA_RESULT. maxDurationSeconds is an integer 2–30; original must be 2–120 seconds and at most 200 MiB. Reads private stored bytes, transcodes locally, probes the output and verifies actual duration does not exceed the requested limit. Preserves the original; returns StoredFileResponse for the new clip. No provider submission or billing. Authentication required; other owners or purposes 403, missing file 404, invalid input 400, storage/processing failure 502.");
    var clipRequest=(ObjectNode)schemas.path("request_storage.interfaces.http.StorageController_ReferenceClipRequest");
    clipRequest.putArray("required").add("maxDurationSeconds");
    ((ObjectNode)clipRequest.path("properties").path("maxDurationSeconds")).put("minimum",2).put("maximum",30);
    depthSemantics(paths);
    ((ObjectNode)paths.path("/api/v1/admin/users/{id}/nickname").path("patch")).put("description",
        "ADMIN or NORMAL_ADMIN with session-bound CSRF. Updates only the user's display nickname, including disabled users. Requires 1 to 64 UTF-16 code units, no blank-only value or control characters; surrounding whitespace is stripped. Phone, avatar, status and auth version are unchanged. Normal administrators receive a masked phone number.");
    var nicknameUpdate=(ObjectNode)schemas.path("request_user.interfaces.http.UserIdentityController_NicknameUpdate");
    nicknameUpdate.putArray("required").add("nickname");
    ((ObjectNode)nicknameUpdate.path("properties")).set("nickname", JSON.valueToTree(Map.of("type","string","minLength",1,"maxLength",64)));
    ((ObjectNode)paths.path("/api/v1/media/product-suites").path("post")).put("description",
        "Atomically creates an owner-scoped suite and one ordinary IMAGE task per selected module. Same owner and Idempotency-Key with identical body replays without new tasks; changed body returns 409. Parent has no additional charge; each child uses existing generation pricing and refunds. Quote is current pricing, not a price lock.");
    ((ObjectNode)paths.path("/api/v1/media/product-suites/{suiteId}/modules/{module}/regenerations").path("post")).put("description",
        "Regenerate a latest terminal module using its original prompt and references and current model availability/pricing. sourceTaskId must match latest attempt; stale or running attempts return 409. Retry the same Idempotency-Key after uncertain responses. Previous task records and results remain available.");
    ((ObjectNode)paths.path("/api/v1/media/product-suites/copy").path("post")).put("description",
        "Optional server-configured image-to-text assistance for owned uploaded images. Disabled/upstream failure returns 503. No platform daily call limit. Does not charge image-generation points.");
    ((ObjectNode)paths.path("/api/v1/media/product-suites/video-breakdown").path("post")).put("description",
        "Analyze 1 to 8 owned uploaded video frame images in chronological order. Timestamps must be strictly increasing and below durationSeconds (0 < duration <= 120). Returns sampled visual shots, not exact cut detection; narration is empty because no audio is supplied. No image/video generation charge or daily assistance limit. Disabled/upstream failure returns 503.");
    checkJson(ROOT.resolve("contracts/openapi/public/xzwl-new-system-1.0.0.json"), document);
    checkJson(ROOT.resolve("gateways/gateway-service/src/main/resources/public-routes.json"), routeList);
    Path inventoryFile = ROOT.resolve("docs/release-controller-inventory.md");
    if (Boolean.getBoolean("writeReleaseContracts")) Files.writeString(inventoryFile, inventory);
    assertThat(Files.readString(inventoryFile).replace("\r\n", "\n")).isEqualTo(inventory.toString());
  }

  // Processing admission accepts only an owned file and a resolution, never client timing metadata.
  void depthSemantics(ObjectNode paths) {
    ((ObjectNode) paths.path("/api/v1/media/depth-options").path("get")).put("description",
        "Local depth capability. enabled is true only for the local profile with the explicit depth flag and worker token. No commercial billing is configured by this increment.");
    ((ObjectNode) paths.path("/api/v1/media/depth-tasks").path("post")).put("description",
        "Submit owned sourceFileId for silent relative-depth video. Server reads video-track metadata: duration <=30 seconds permits 480p/720p, otherwise only 480p. Preserve source display aspect, frame timestamps, frame rate and full video duration. Reuse existing task query/results and owner-scoped idempotency. Local feature only; disabled returns 503. Does not invoke Ark, Routing, MPS or Billing.");
    var submission = (ObjectNode) schemas.path("request_media.interfaces.http.DepthController_Submission");
    submission.put("additionalProperties", false);
    submission.set("required", JSON.valueToTree(List.of("sourceFileId", "resolution")));
    var properties = (ObjectNode) submission.path("properties");
    properties.set("sourceFileId", JSON.valueToTree(Map.of("type", "string", "pattern", "^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")));
    properties.set("resolution", JSON.valueToTree(Map.of("type", "string", "enum", List.of("480p", "720p"))));
    ((ObjectNode) schemas.path("request_media.domain.task.TaskRequest").path("properties"))
        .set("operation", JSON.valueToTree(Map.of("type", "string", "enum", List.of("GENERATION"), "default", "GENERATION",
            "description", "Ordinary generation admission only. DEPTH must use the dedicated sourceFileId endpoint.")));
    ((ObjectNode) paths.path("/api/v1/files/processing-videos").path("post")).put("description",
        "Upload MP4/MOV processing input up to 200 MiB. Storage probes video-track duration and frame metadata. Does not apply OMNI 2-30 second or 24-60 fps limits; reusing this file for OMNI still enforces the original OMNI limits.");
    assertThat(paths.has("/internal/v1/files/processing-videos/access-url")).isFalse();
  }

  // Portrait admission and upstream state constraints supplement the compiled wire types.
  void portraitSemantics(ObjectNode paths) {
    {
      var op=(ObjectNode)paths.path("/api/v1/media/portraits").path("post");
      op.put("operationId","media_portrait_submit_post__api_v1_media_portraits");
      op.put("description","Upload portrait flow: automatically submit an owned stored virtual portrait image, video or audio for review. Optional kind defaults to IMAGE and must match Storage metadata; VIDEO/AUDIO duration comes from Storage. Persist submission before upstream writes. Repeat owner/Idempotency-Key with identical original body replays its existing record even after configuration changes; different body returns 409. A new key may reuse the same owner/file/model/provider/credential identity/project record. UNKNOWN submission remains UNCERTAIN and is not resent. Does not charge video generation points.");
      ((ObjectNode)op.path("parameters").get(0).path("schema")).put("pattern","^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$");
    }
    {
      var op=(ObjectNode)paths.path("/api/v1/media/portraits").path("get");
      op.put("operationId","media_portrait_list_get__api_v1_media_portraits");
      op.put("description","Lists only caller-owned portraits newest first with exclusive beforeId cursor; no upstream asset creation. Poll each PROCESSING record through the detail operation. ACTIVE reuse still requires current model and provider credential identity validation.");
      var limit=(ObjectNode)op.path("parameters").get(0).path("schema");
      limit.remove("format");
      limit.put("minimum",1).put("maximum",100);
    }
    {
      var op=(ObjectNode)paths.path("/api/v1/media/portraits/{portraitId}").path("get");
      op.put("operationId","media_portrait_get_get__api_v1_media_portraits_portraitId");
      op.put("description","Reads an owner-scoped review record and refreshes known upstream asset status without creating assets. Missing/other-owner IDs return 404. Only matching Active assets are ACTIVE; failed review/configuration mismatch is not usable. No provider credentials, asset URLs, or raw upstream messages in response.");
    }
    var request=(ObjectNode)schemas.path("request_media.interfaces.http.PortraitController_SubmitRequest");
    request.put("additionalProperties",false);
    request.set("required",JSON.valueToTree(List.of("fileId","model")));
    for(String field:List.of("fileId","model")) {
      ((ObjectNode)request.path("properties")).set(field,JSON.valueToTree(Map.of("type","string","minLength",1,"maxLength",128,"pattern","^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")));
    }
    ((ObjectNode)request.path("properties")).set("kind",JSON.valueToTree(Map.of("type","string","enum",List.of("IMAGE","VIDEO","AUDIO"),"default","IMAGE")));
    var response=(ObjectNode)schemas.path("response_media.interfaces.http.PortraitController_PortraitResponse");
    ((ObjectNode)response.path("properties")).set("kind",JSON.valueToTree(Map.of("type","string","enum",List.of("IMAGE","VIDEO","AUDIO"))));
    ((ObjectNode)response.path("properties").path("durationSeconds")).put("description","Trusted Storage duration in seconds for VIDEO/AUDIO; null for IMAGE.");
    response.set("required",JSON.valueToTree(List.of("id","fileId","model","status","errorMessage","kind","durationSeconds")));
    for(String field:List.of("id","fileId","model")) {
      ((ObjectNode)response.path("properties")).set(field,JSON.valueToTree(Map.of("type","string")));
    }
    ((ObjectNode)response.path("properties")).set("status",JSON.valueToTree(Map.of("type","string","enum",List.of("SUBMITTING","PROCESSING","ACTIVE","FAILED","UNCERTAIN"))));
    for(String prefix:List.of("request_","response_")) {
      var reference=(ObjectNode)schemas.path(prefix+"media.domain.task.MediaReference");
      var portrait=(ObjectNode)reference.path("properties").path("portraitId");
      ((ObjectNode)portrait.path("anyOf").get(0)).put("pattern","^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$");
      portrait.put("description","Optional platform portrait review ID. Requires owned fileId and matching IMAGE, VIDEO or AUDIO kind in VIDEO OMNI mode. Server verifies owner, original file, model, provider credential identity/project and Active review before charging. Never a provider asset URL.");
      if(prefix.equals("response_")) reference.set("required",JSON.valueToTree(List.of("durationSeconds","fileId","httpsUrl","kind")));
    }
  }

  @Test void canvasListSchemaDeclaresRuntimeBounds() throws Exception {
    var doc = JSON.readTree(ROOT.resolve("contracts/openapi/public/xzwl-new-system-1.0.0.json").toFile());
    var params = doc.at("/paths/~1api~1v1~1canvases/get/parameters");
    assertThat(params.get(0).path("schema").path("minimum").asInt(-1)).isZero();
    assertThat(params.get(1).path("schema").path("minimum").asInt()).isEqualTo(1);
    assertThat(params.get(1).path("schema").path("maximum").asInt()).isEqualTo(24);
    var preview = doc.at("/components/schemas/response_canvas.interfaces.CanvasController_CanvasSummary/properties/previewNodes");
    assertThat(preview.path("maxItems").asInt()).isEqualTo(8);
    var properties = preview.at("/items/properties/properties");
    assertThat(properties.path("additionalProperties").asBoolean(true)).isFalse();
    assertThat(properties.path("properties").properties().stream().map(Map.Entry::getKey).toList())
        .containsExactlyInAnyOrder("x", "y", "text", "alt", "publicId");
    assertThat(properties.at("/properties/text/maxLength").asInt()).isEqualTo(160);
    assertThat(properties.at("/properties/alt/maxLength").asInt()).isEqualTo(160);
  }

  @Test void publishedDocumentHasResolvableReferencesAndDistinctOperations() throws Exception {
    var doc = JSON.readTree(ROOT.resolve("contracts/openapi/public/xzwl-new-system-1.0.0.json").toFile());
    assertThat(doc.path("openapi").asText()).isEqualTo("3.1.0");
    Set<String> ids = new HashSet<>();
    doc.path("paths").properties().forEach(path -> {
      assertThat(path.getKey()).startsWith("/").doesNotContain("/internal/");
      path.getValue().properties().forEach(operation -> {
        assertThat(ids.add(operation.getValue().path("operationId").asText())).isTrue();
        assertThat(operation.getValue().path("responses").size()).isPositive();
      });
    });
    checkReferences(doc, doc);
    var deletion = doc.at("/paths/~1api~1v1~1admin~1routing~1models~1{modelId}/delete");
    assertThat(deletion.path("responses").has("204")).isTrue();
    assertThat(deletion.path("responses").path("204").has("content")).isFalse();
    assertThat(deletion.path("security").get(0).has("csrfToken")).isTrue();
    var login = doc.at("/paths/~1api~1v1~1auth~1otp~1login/post");
    assertThat(login.path("security").get(0).has("sessionCookie")).isTrue();
    assertThat(login.path("security").get(0).has("csrfToken")).isTrue();
    assertThat(login.path("responses").has("200")).isTrue();
    assertThat(doc.at("/paths/~1api~1v1~1media~1tasks/post/responses").has("202")).isTrue();
    assertThat(doc.at("/paths/~1api~1v1~1files~1avatars/post/requestBody/content").has("multipart/form-data")).isTrue();
  }
  @Test void canvasRequestSchemasRespectRequiredNonNullConstructorInputs() throws Exception {
    var doc = JSON.readTree(ROOT.resolve("contracts/openapi/public/xzwl-new-system-1.0.0.json").toFile());
    var create = doc.at("/components/schemas/request_canvas.interfaces.CanvasController_CreateRequest");
    assertThat(create.path("required")).isEqualTo(JSON.valueToTree(List.of("name")));
    assertThat(create.at("/properties/name/type").asText()).isEqualTo("string");
    var save = doc.at("/components/schemas/request_canvas.interfaces.CanvasController_SaveRequest");
    assertThat(save.path("required")).isEqualTo(JSON.valueToTree(List.of("content", "expectedVersion")));
    var content = doc.at("/components/schemas/request_canvas.domain.CanvasContent");
    assertThat(content.path("required")).isEqualTo(JSON.valueToTree(List.of("edges", "nodes", "viewport")));
    assertThat(content.path("properties").has("empty")).isFalse();
    var viewport = doc.at("/components/schemas/request_canvas.domain.CanvasContent_Viewport");
    assertThat(viewport.path("required")).isEqualTo(JSON.valueToTree(List.of("zoom")));
    assertThat(viewport.at("/properties/x/type").asText()).isEqualTo("number");
  }
  static void checkReferences(JsonNode root, JsonNode value) {
    if (value.has("$ref")) {
      String ref = value.path("$ref").asText();
      assertThat(ref).startsWith("#/components/");
      assertThat(root.at(ref.substring(1)).isMissingNode()).as(ref).isFalse();
    }
    value.elements().forEachRemaining(child -> checkReferences(root, child));
  }

  ObjectNode operation(Endpoint e) throws Exception {
    ObjectNode op = JSON.createObjectNode();
    op.put("operationId", e.service + "_" + (e.method == null ? "logout" : e.method.getName()) + "_" +
        e.verb.toLowerCase(Locale.ROOT) + "_" + e.path.replaceAll("[^A-Za-z0-9]", "_"));
    op.set("tags", JSON.valueToTree(List.of(e.service)));
    op.put("x-controller-source", e.source.toString().replace('\\', '/') + (e.method == null ? "#logout" : "#" + e.method.getName()));
    boolean callback = e.path.equals("/callbacks/payment");
    boolean anonymous = e.path.equals("/api/v1/auth/session") || e.path.equals("/api/v1/auth/admin/login") || e.path.startsWith("/api/v1/auth/otp/");
    Map<String, Object> security = new LinkedHashMap<>();
    if (!callback) {
      if (!anonymous || !e.verb.equals("GET")) security.put("sessionCookie", List.of());
      if (!e.verb.equals("GET")) security.put("csrfToken", List.of());
    }
    boolean apiKey = (e.service.equals("media") || e.service.equals("storage"))
        && com.xzwl.cloud.web.PlatformApiKeyFilter.accepts(e.verb, e.path.replace("{id}", "id").replace("{taskId}", "task").replace("{fileId}", "file").replace("{portraitId}", "portrait"));
    op.set("security", JSON.valueToTree(apiKey ? List.of(security, Map.of("platformKey", List.of())) : security.isEmpty() ? List.of() : List.of(security)));
    if (callback) op.put("description", "Provider-signed raw callback body; signature verification is delegated to PaymentProvider. No browser Session/CSRF. Provider headers/body are not yet frozen.");
    else if (e.path.contains("/admin/") || e.path.equals("/api/v1/settings/runtime"))
      op.put("description", "Authorization follows the actual Controller/service checks; consult the per-service role table in the release guide.");
    ArrayNode parameters = op.putArray("parameters");
    if (e.method != null) for (Parameter parameter : e.method.getParameters()) {
      var path = parameter.getAnnotation(PathVariable.class);
      var query = parameter.getAnnotation(RequestParam.class);
      var header = parameter.getAnnotation(RequestHeader.class);
      var body = parameter.getAnnotation(RequestBody.class);
      var part = parameter.getAnnotation(RequestPart.class);
      if (path != null || query != null || header != null) {
        String name = path != null ? name(path.name(), path.value(), parameter) : query != null ? name(query.name(), query.value(), parameter) : name(header.name(), header.value(), parameter);
        var p = parameters.addObject().put("name", name).put("in", path != null ? "path" : query != null ? "query" : "header");
        String defaultValue = query != null ? query.defaultValue() : header != null ? header.defaultValue() : ValueConstants.DEFAULT_NONE;
        p.put("required", path != null || (query != null ? query.required() : header.required()) && defaultValue.equals(ValueConstants.DEFAULT_NONE));
        var schema = schema(JSON.constructType(parameter.getParameterizedType()));
        if (!defaultValue.equals(ValueConstants.DEFAULT_NONE)) {
          schema = schema.deepCopy();
          ((ObjectNode)schema).set("default", parameter.getType() == int.class ? JSON.valueToTree(Integer.parseInt(defaultValue)) : JSON.valueToTree(defaultValue));
        }
        p.set("schema", schema);
      } else if (body != null) {
        JsonNode schema = schema(JSON.constructType(parameter.getParameterizedType()), true);
        if (parameter.getType() == JsonNode.class) {
          assertThat(e.path).isEqualTo("/api/v1/settings/runtime");
          schema = object(Map.of("rechargePointsPerYuan4", JSON.valueToTree(Map.of("type", "string")), "rechargeRateVersion", JSON.valueToTree(Map.of("type", "integer", "format", "int64"))), true);
        }
        op.set("requestBody", requestBody("application/json", schema, body.required()));
      } else if (part != null) {
        assertThat(parameter.getType()).isIn(MultipartFile.class, String.class);
        if (!op.has("requestBody")) op.set("requestBody", requestBody("multipart/form-data", object(Map.of(), false), true));
        var form = (ObjectNode)op.path("requestBody").path("content").path("multipart/form-data").path("schema");
        String partName = name(part.name(), part.value(), parameter);
        form.withObjectProperty("properties").set(partName, JSON.valueToTree(parameter.getType() == MultipartFile.class
            ? Map.of("type", "string", "format", "binary") : Map.of("type", "string")));
        if (part.required()) form.withArray("required").add(partName);
      }
    }
    if (parameters.isEmpty()) op.remove("parameters");
    if (callback) op.set("requestBody", requestBody("*/*", JSON.valueToTree(Map.of("type", "string", "format", "binary")), false));
    int status = 200;
    if (e.method != null) {
      var annotation = AnnotatedElementUtils.findMergedAnnotation(e.method, ResponseStatus.class);
      if (annotation != null) status = annotation.code().value();
    }
    var responses = op.putObject("responses");
    if (e.method != null && e.method.getReturnType() == void.class) {
      assertThat(status).isEqualTo(204);
      responses.putObject("204").put("description", "No Content");
    } else responses.set(Integer.toString(status), response(HttpStatus.valueOf(status).getReasonPhrase(), returnSchema(e)));
    List<String> variants = new ArrayList<>(List.of("ErrorEnvelope", "CodeError", "ServiceError"));
    if (e.service.equals("storage")) variants.add("MessageError");
    JsonNode errorSchema = JSON.valueToTree(Map.of("anyOf", variants.stream().map(v -> Map.of("$ref", "#/components/schemas/" + v)).toList()));
    errorResponses.set(e.service + "Error", response("Controller/advice/filter or Gateway error; HTTP status and original body are preserved. See the release guide for code meanings.", errorSchema));
    JsonNode errorRef = JSON.valueToTree(Map.of("$ref", "#/components/responses/" + e.service + "Error"));
    for (int code : errorStatuses(e)) responses.set(Integer.toString(code), errorRef);
    responses.set("default", errorRef);
    return op;
  }

  // These statuses come from each Controller's reachable validation/advice and browser filters.
  // Successful statuses are never supplied here: Spring @ResponseStatus is authoritative.
  static Set<Integer> errorStatuses(Endpoint e) {
    Set<Integer> result = new TreeSet<>(List.of(400, 500, 502, 503));
    if (!e.path.equals("/callbacks/payment")) {
      result.add(403); // IAM CSRF / role checks (including anonymous login).
      if (!e.path.equals("/api/v1/auth/session") && !e.path.equals("/api/v1/auth/otp/send")) result.add(401);
    }
    switch (e.service) {
      case "iam" -> {
        if (e.path.equals("/api/v1/auth/otp/send")) result.add(429);
        if (e.path.equals("/api/v1/auth/otp/login")) result.add(423);
        if (e.path.startsWith("/api/v1/admin/admins/")) result.add(404);
        if (e.path.equals("/api/v1/admin/admins") && e.verb.equals("POST")) result.add(409);
      }
      case "routing" -> { }
      case "user" -> { result.add(404); if (e.verb.equals("POST")) result.add(409); }
      case "access" -> { result.add(404); if (e.verb.equals("POST")) result.add(409); }
      case "billing" -> { if (!e.verb.equals("GET")) result.add(409); if(e.path.startsWith("/api/v1/admin/billing/consumptions/")) result.add(404); }
      case "payment" -> { result.add(404); result.add(409); if (e.path.equals("/callbacks/payment")) result.add(413); }
      case "canvas" -> { result.add(404); if (!e.verb.equals("GET")) result.add(409); }
      case "media" -> {
        result.add(404);
        if (e.path.startsWith("/api/v1/media/portraits")) { result.add(409); result.add(422); }
        if (e.verb.equals("POST") || (e.verb.equals("PUT") && Set.of("/api/v1/admin/media/ark-connection","/api/v1/admin/media/mps-configuration").contains(e.path))) result.add(409);
        if (e.path.equals("/api/v1/media/generation-options") || e.path.equals("/api/v1/media/quotes")
            || (e.path.equals("/api/v1/media/tasks") && e.verb.equals("POST"))) result.add(422);
      }
      case "storage" -> { result.add(404); if (e.verb.equals("POST")) result.add(413); }
      case "promotion", "operations" -> { result.add(404); if (!e.verb.equals("GET")) result.add(409); }
      default -> throw new IllegalArgumentException(e.service);
    }
    return result;
  }

  JsonNode returnSchema(Endpoint e) {
    if (e.method == null) return object(Map.of("success", JSON.valueToTree(Map.of("type", "boolean", "const", true))), true);
    JavaType type = JSON.constructType(e.method.getGenericReturnType());
    if (type.getRawClass() == org.springframework.http.ResponseEntity.class) type = type.containedType(0);
    if (Map.class.isAssignableFrom(type.getRawClass())) {
      String source;
      try { source = Files.readString(ROOT.resolve(e.source)); } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
      assertThat(source).contains("Map.of(");
      if (e.method.getName().equals("send")) return object(Map.of("success", JSON.valueToTree(Map.of("type", "boolean", "const", true)),
          "expiresInSeconds", JSON.valueToTree(Map.of("type", "integer", "const", 300)), "resendAfterSeconds", JSON.valueToTree(Map.of("type", "integer", "const", 60))), true);
      assertThat(e.method.getName()).isIn("send", "secret", "adminSecret", "callback", "login", "status", "password", "reset", "role");
      String key = Set.of("secret", "adminSecret").contains(e.method.getName()) ? "secret" : e.method.getName().equals("callback") ? "accepted" : "success";
      assertThat(source).contains("Map.of(\"" + key + "\",");
      return object(Map.of(key, JSON.valueToTree(key.equals("secret") ? Map.of("type", "string") : Map.of("type", "boolean", "const", true))), true);
    }
    return schema(type);
  }

  JsonNode schema(JavaType type) { return schema(type, false); }

  JsonNode schema(JavaType type, boolean input) {
    Class<?> raw = type.getRawClass();
    if (raw == Object.class || JsonNode.class.isAssignableFrom(raw)) return JSON.createObjectNode();
    if (raw == String.class || raw == java.net.URI.class) return JSON.valueToTree(Map.of("type", "string"));
    if (raw == Instant.class || raw == OffsetDateTime.class) return JSON.valueToTree(Map.of("type", "string", "format", "date-time"));
    if (raw == boolean.class || raw == Boolean.class) return JSON.valueToTree(Map.of("type", "boolean"));
    if (raw == int.class || raw == Integer.class) return JSON.valueToTree(Map.of("type", "integer", "format", "int32"));
    if (raw == long.class || raw == Long.class) return JSON.valueToTree(Map.of("type", "integer", "format", "int64"));
    if (raw == double.class || raw == float.class || Number.class.isAssignableFrom(raw)) return JSON.valueToTree(Map.of("type", "number"));
    if (raw.isEnum()) return JSON.valueToTree(Map.of("type", "string", "enum", Arrays.stream(raw.getEnumConstants()).map(Object::toString).toList()));
    if (type.isCollectionLikeType()) return JSON.valueToTree(Map.of("type", "array", "items", schema(type.getContentType(), input)));
    if (type.isMapLikeType()) return JSON.valueToTree(Map.of("type", "object", "additionalProperties", schema(type.getContentType(), input)));
    assertThat(raw.isRecord()).as("Explicit schema needed for " + type).isTrue();
    String id = (input ? "request_" : "response_") + type.toCanonical().replace("com.xzwl.cloud.", "").replaceAll("[^A-Za-z0-9._-]", "_");
    if (!schemas.has(id)) {
      schemas.putObject(id);
      var properties = new TreeMap<String, JsonNode>();
      for (var prop : JSON.getSerializationConfig().introspect(type).findProperties()) {
        JsonNode value = schema(prop.getPrimaryType(), input);
        // Jackson emits null reference members; do not manufacture non-null validation constraints.
        if (!raw.getName().startsWith("com.xzwl.cloud.canvas.") && !prop.getPrimaryType().isPrimitive()) value = JSON.valueToTree(Map.of("anyOf", List.of(value, Map.of("type", "null"))));
        properties.put(prop.getName(), value);
      }
      // Request-member requirements belong to Controller/domain validation; only @RequestBody is mandatory here.
      ObjectNode definition = object(properties, !input || raw.getName().startsWith("com.xzwl.cloud.canvas."));
      if (input && raw == com.xzwl.cloud.canvas.domain.CanvasContent.Viewport.class) {
        // Missing x/y are primitive zero defaults; missing zoom fails the production constructor.
        definition.set("required", JSON.valueToTree(List.of("zoom")));
      }
      if (input && raw == com.xzwl.cloud.canvas.interfaces.CanvasController.SaveRequest.class) {
        definition.set("required", JSON.valueToTree(List.of("content", "expectedVersion")));
        ((ObjectNode) definition.path("properties")).set("name", JSON.valueToTree(Map.of("anyOf", List.of(Map.of("type", "string"), Map.of("type", "null")))));
      }
      schemas.set(id, definition);
    }
    return JSON.valueToTree(Map.of("$ref", "#/components/schemas/" + id));
  }
  static ObjectNode object(Map<String, JsonNode> properties, boolean required) {
    ObjectNode result = JSON.createObjectNode().put("type", "object");
    result.set("properties", JSON.valueToTree(new TreeMap<>(properties)));
    if (required) result.set("required", JSON.valueToTree(new TreeSet<>(properties.keySet())));
    return result;
  }
  static JsonNode requestBody(String contentType, JsonNode schema, boolean required) {
    return JSON.valueToTree(Map.of("required", required, "content", Map.of(contentType, Map.of("schema", schema))));
  }
  static JsonNode response(String description, JsonNode schema) {
    return JSON.valueToTree(Map.of("description", description, "content", Map.of("application/json", Map.of("schema", schema))));
  }
  static String name(String name, String value, Parameter p) {
    String result = !name.isEmpty() ? name : !value.isEmpty() ? value : p.getName();
    assertThat(result).doesNotMatch("arg[0-9]+"); return result;
  }
  static void checkJson(Path file, JsonNode expected) throws Exception {
    if (Boolean.getBoolean("writeReleaseContracts")) {
      Files.createDirectories(file.getParent());
      Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(expected) + "\n");
    }
    assertThat(Files.exists(file)).as(file.toString()).isTrue();
    assertThat(JSON.readTree(file.toFile())).isEqualTo(expected);
  }
}
