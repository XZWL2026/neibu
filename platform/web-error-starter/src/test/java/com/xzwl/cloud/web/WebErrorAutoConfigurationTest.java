package com.xzwl.cloud.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@ExtendWith(OutputCaptureExtension.class)
class WebErrorAutoConfigurationTest {
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String SAFE_REQUEST_ID_PATTERN = "[A-Za-z0-9._-]{1,64}";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WebErrorAutoConfiguration.class));

    @Test
    void autoConfigurationRegistersFilterAndExceptionHandler() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RequestIdFilter.class);
            assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
        });
    }

    @Test
    void autoConfigurationIsDeclaredInBootImports() throws Exception {
        String location = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

        try (InputStream resource = getClass().getClassLoader().getResourceAsStream(location)) {
            assertThat(resource).isNotNull();
            List<String> candidates = Arrays.stream(
                    new String(resource.readAllBytes(), StandardCharsets.UTF_8).split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
            assertThat(candidates).containsExactly(WebErrorAutoConfiguration.class.getName());
        }
    }

    @Test
    void errorEnvelopeHasExactlyTheProtocolFields() {
        assertThat(Arrays.stream(ErrorEnvelope.class.getRecordComponents())
            .map(component -> component.getName())
            .toList()).containsExactly("code", "message", "requestId", "details");
    }

    @Test
    void errorEnvelopeDefensivelyCopiesTopLevelDetails() {
        Map<String, Object> source = new HashMap<>();
        source.put("field", "email");

        ErrorEnvelope envelope = new ErrorEnvelope("INVALID", "invalid input", "request-1", source);
        source.put("field", "mutated");
        source.put("extra", "secret");

        assertThat(envelope.details()).containsExactlyEntriesOf(Map.of("field", "email"));
        assertThatThrownBy(() -> envelope.details().put("other", "value"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void missingRequestIdGeneratesSafeIdSharedByHeaderAndUnknownErrorBody() throws Exception {
        MvcResult result = mockMvc(new BusinessException("BUSINESS", "rejected", Map.of()))
            .perform(get("/unknown"))
            .andExpect(status().isInternalServerError())
            .andExpect(header().exists(REQUEST_ID_HEADER))
            .andReturn();

        assertSafeRequestIdAndBodyMatch(result);
    }

    @Test
    void validRequestIdIsPassedThrough() throws Exception {
        String requestId = "Request_123-safe.id";

        mockMvc(new BusinessException("BUSINESS", "rejected", Map.of()))
            .perform(get("/unknown").header(REQUEST_ID_HEADER, requestId))
            .andExpect(status().isInternalServerError())
            .andExpect(header().string(REQUEST_ID_HEADER, requestId))
            .andExpect(jsonPath("$.requestId").value(requestId));
    }

    @Test
    void requestIdLengthBoundariesAreEnforced() throws Exception {
        MockMvc mockMvc = mockMvc(new BusinessException("BUSINESS", "rejected", Map.of()));

        for (String valid : List.of("a", "a".repeat(64))) {
            mockMvc.perform(get("/unknown").header(REQUEST_ID_HEADER, valid))
                .andExpect(header().string(REQUEST_ID_HEADER, valid))
                .andExpect(jsonPath("$.requestId").value(valid));
        }

        MvcResult tooLong = mockMvc.perform(
                get("/unknown").header(REQUEST_ID_HEADER, "a".repeat(65)))
            .andReturn();
        assertSafeRequestIdAndBodyMatch(tooLong);
        assertThat(tooLong.getResponse().getHeader(REQUEST_ID_HEADER)).hasSizeLessThanOrEqualTo(64);
    }

    @Test
    void unsafeRequestIdsAreReplacedWithoutResponseHeaderInjection() throws Exception {
        MockMvc mockMvc = mockMvc(new BusinessException("BUSINESS", "rejected", Map.of()));

        for (String unsafe : List.of(
            "",
            "   ",
            "contains space",
            "unsafe/value",
            "非ASCII",
            "safe\r\nX-Evil: injected",
            "tab\tvalue",
            "nul\0value",
            "delete\u007fvalue"
        )) {
            MvcResult result = mockMvc.perform(get("/unknown").header(REQUEST_ID_HEADER, unsafe))
                .andExpect(status().isInternalServerError())
                .andReturn();

            assertSafeRequestIdAndBodyMatch(result);
            assertThat(result.getResponse().getHeader(REQUEST_ID_HEADER)).isNotEqualTo(unsafe);
            assertThat(result.getResponse().getHeaderNames()).doesNotContain("X-Evil");
        }
    }

    @Test
    void businessExceptionUsesFixedEnvelopeAndSnapshotOfDetails() throws Exception {
        Map<String, Object> source = new HashMap<>();
        source.put("field", "email");
        BusinessException exception = new BusinessException("INVALID_EMAIL", "email is invalid", source);
        source.put("field", "mutated");
        source.put("sql", "select secret from users");
        String requestId = "business-request-1";

        mockMvc(exception)
            .perform(get("/business").header(REQUEST_ID_HEADER, requestId))
            .andExpect(status().isBadRequest())
            .andExpect(header().string(REQUEST_ID_HEADER, requestId))
            .andExpect(jsonPath("$.code").value("INVALID_EMAIL"))
            .andExpect(jsonPath("$.message").value("email is invalid"))
            .andExpect(jsonPath("$.requestId").value(requestId))
            .andExpect(jsonPath("$.details.field").value("email"))
            .andExpect(jsonPath("$.details.sql").doesNotExist());
    }

    @Test
    void unknownExceptionReturnsOnlySafeGenericEnvelopeAndLogsByRequestId(
        CapturedOutput output
    ) throws Exception {
        String requestId = "unknown-request-1";

        MvcResult result = mockMvc(new BusinessException("BUSINESS", "rejected", Map.of()))
            .perform(get("/unknown")
                .header(REQUEST_ID_HEADER, requestId)
                .header("Authorization", "Bearer authentication-secret")
                .cookie(new Cookie("session", "cookie-secret"))
                .content("request-body-secret"))
            .andExpect(status().isInternalServerError())
            .andExpect(header().string(REQUEST_ID_HEADER, requestId))
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.message").value("Internal server error"))
            .andExpect(jsonPath("$.requestId").value(requestId))
            .andExpect(jsonPath("$.details").isEmpty())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        List<String> fields = new ArrayList<>();
        OBJECT_MAPPER.readTree(body).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("code", "message", "requestId", "details");
        assertThat(body)
            .doesNotContain("IllegalStateException")
            .doesNotContain("java.lang")
            .doesNotContain("stack")
            .doesNotContain("select")
            .doesNotContain("password")
            .doesNotContain("sensitive");

        assertThat(output)
            .contains("requestId=" + requestId)
            .contains("IllegalStateException")
            .contains("sensitive password appears in SQL")
            .doesNotContain("authentication-secret")
            .doesNotContain("cookie-secret")
            .doesNotContain("request-body-secret");
    }

    @Test
    void cyclicCauseGraphCannotPinUnknownExceptionHandling() throws Exception {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second");
        first.initCause(second);
        second.initCause(first);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "cyclic-cause-request");
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "cyclic-cause-test");
            thread.setDaemon(true);
            return thread;
        });

        try {
            var future = executor.submit(
                () -> new GlobalExceptionHandler().handleUnknownException(first, request)
            );
            var response = future.get(1, TimeUnit.SECONDS);

            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody().requestId()).isEqualTo("cyclic-cause-request");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void filterPublishesRequestAttributeAndAlwaysWritesResponseHeader() throws Exception {
        MvcResult result = mockMvc(new BusinessException("BUSINESS", "rejected", Map.of()))
            .perform(get("/request-id"))
            .andExpect(status().isOk())
            .andExpect(header().exists(REQUEST_ID_HEADER))
            .andReturn();

        String headerRequestId = result.getResponse().getHeader(REQUEST_ID_HEADER);
        assertThat(result.getResponse().getContentAsString()).isEqualTo(headerRequestId);
        assertThat(headerRequestId).matches(SAFE_REQUEST_ID_PATTERN);
    }

    @Test
    void filterDoesNotTreatAuthorizationAsAuthentication() throws Exception {
        mockMvc(new BusinessException("BUSINESS", "rejected", Map.of()))
            .perform(get("/principal").header("Authorization", "Bearer opaque-token"))
            .andExpect(status().isOk())
            .andExpect(content().string("none"))
            .andExpect(header().exists(REQUEST_ID_HEADER));
    }

    private static MockMvc mockMvc(BusinessException businessException) {
        return MockMvcBuilders.standaloneSetup(new TestController(businessException))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new RequestIdFilter())
            .build();
    }

    private static void assertSafeRequestIdAndBodyMatch(MvcResult result) throws Exception {
        String headerRequestId = result.getResponse().getHeader(REQUEST_ID_HEADER);
        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsByteArray());

        assertThat(headerRequestId).matches(SAFE_REQUEST_ID_PATTERN);
        assertThat(body.path("requestId").asText()).isEqualTo(headerRequestId);
    }

    @RestController
    private static final class TestController {
        private final BusinessException businessException;

        private TestController(BusinessException businessException) {
            this.businessException = businessException;
        }

        @GetMapping("/business")
        Object business() {
            throw businessException;
        }

        @GetMapping("/unknown")
        Object unknown() {
            throw new IllegalStateException(
                "sensitive password appears in SQL: select password from users");
        }

        @GetMapping("/request-id")
        Object requestId(HttpServletRequest request) {
            return request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        }

        @GetMapping("/principal")
        String principal(HttpServletRequest request) {
            return request.getUserPrincipal() == null ? "none" : "present";
        }
    }
}
