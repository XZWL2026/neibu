package com.xzwl.cloud.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(classes = WebErrorAutoConfigurationIntegrationTest.TestApplication.class)
@AutoConfigureMockMvc
class WebErrorAutoConfigurationIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void bootImportsAutoConfigureFilterAndExceptionHandlerInRealMvcChain() throws Exception {
        MvcResult result = mockMvc.perform(get("/auto-configured/unknown"))
            .andExpect(status().isInternalServerError())
            .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER))
            .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.INTERNAL_ERROR_CODE))
            .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.INTERNAL_ERROR_MESSAGE))
            .andExpect(jsonPath("$.details").isEmpty())
            .andReturn();

        String headerRequestId = result.getResponse().getHeader(RequestIdFilter.REQUEST_ID_HEADER);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(headerRequestId).matches("[A-Za-z0-9._-]{1,64}");
        assertThat(body.path("requestId").asText()).isEqualTo(headerRequestId);
    }

    @Test
    void missingRouteKeepsNotFoundStatusWithSafeEnvelope() throws Exception {
        String requestId = "missing-route-request";

        MvcResult result = mockMvc.perform(
                get("/route-that-does-not-exist").header(RequestIdFilter.REQUEST_ID_HEADER, requestId))
            .andExpect(status().isNotFound())
            .andReturn();

        assertClassifiedError(result, requestId, "NOT_FOUND", "Not Found");
        assertThat(result.getResponse().getContentAsString())
            .doesNotContain("NoResourceFoundException")
            .doesNotContain("route-that-does-not-exist");
    }

    @Test
    void responseStatusExceptionKeepsStatusWithoutLeakingReason() throws Exception {
        String requestId = "response-status-request";

        MvcResult result = mockMvc.perform(
                get("/auto-configured/response-status")
                    .header(RequestIdFilter.REQUEST_ID_HEADER, requestId))
            .andExpect(status().isNotFound())
            .andReturn();

        assertClassifiedError(result, requestId, "NOT_FOUND", "Not Found");
        assertThat(result.getResponse().getContentAsString())
            .doesNotContain("sensitive custom reason")
            .doesNotContain("ResponseStatusException");
    }

    @Test
    void missingRequiredParameterKeepsBadRequestStatusWithSafeEnvelope() throws Exception {
        String requestId = "missing-parameter-request";

        MvcResult result = mockMvc.perform(
                get("/auto-configured/required-parameter")
                    .header(RequestIdFilter.REQUEST_ID_HEADER, requestId))
            .andExpect(status().isBadRequest())
            .andReturn();

        assertClassifiedError(result, requestId, "BAD_REQUEST", "Bad Request");
        assertThat(result.getResponse().getContentAsString())
            .doesNotContain("MissingServletRequestParameterException")
            .doesNotContain("requiredValue");
    }

    @Test
    void responseStatusAnnotatedExceptionKeepsStatusWithoutLeakingReason() throws Exception {
        String requestId = "annotated-status-request";

        MvcResult result = mockMvc.perform(
                get("/auto-configured/annotated-status")
                    .header(RequestIdFilter.REQUEST_ID_HEADER, requestId))
            .andExpect(status().isConflict())
            .andReturn();

        assertClassifiedError(result, requestId, "CONFLICT", "Conflict");
        assertThat(result.getResponse().getContentAsString())
            .doesNotContain("sensitive annotated reason")
            .doesNotContain("internal annotated detail")
            .doesNotContain("AnnotatedConflictException");
    }

    @Test
    void classifiedErrorPreservesProtocolHeadersButCannotOverrideRequestId() throws Exception {
        String requestId = "classified-header-request";

        MvcResult result = mockMvc.perform(
                get("/auto-configured/classified-headers")
                    .header(RequestIdFilter.REQUEST_ID_HEADER, requestId))
            .andExpect(status().isMethodNotAllowed())
            .andReturn();

        assertClassifiedError(
            result,
            requestId,
            "METHOD_NOT_ALLOWED",
            "Method Not Allowed"
        );
        assertThat(result.getResponse().getHeaders(RequestIdFilter.REQUEST_ID_HEADER))
            .containsExactly(requestId);
        assertThat(result.getResponse().getHeaders(HttpHeaders.ALLOW)).containsExactly("GET");
    }

    private void assertClassifiedError(
        MvcResult result,
        String requestId,
        String expectedCode,
        String expectedMessage
    ) throws Exception {
        assertThat(result.getResponse().getHeader(RequestIdFilter.REQUEST_ID_HEADER))
            .isEqualTo(requestId);

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        List<String> fields = new ArrayList<>();
        body.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("code", "message", "requestId", "details");
        assertThat(body.path("code").asText()).isEqualTo(expectedCode);
        assertThat(body.path("message").asText()).isEqualTo(expectedMessage);
        assertThat(body.path("requestId").asText()).isEqualTo(requestId);
        assertThat(body.path("details").isObject()).isTrue();
        assertThat(body.path("details").isEmpty()).isTrue();
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(TestController.class)
    static class TestApplication {
    }

    @RestController
    static class TestController {
        @GetMapping("/auto-configured/unknown")
        Map<String, Object> unknown() {
            throw new IllegalStateException("sensitive SQL: select password from users");
        }

        @GetMapping("/auto-configured/response-status")
        Map<String, Object> responseStatus() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "sensitive custom reason");
        }

        @GetMapping("/auto-configured/required-parameter")
        Map<String, Object> requiredParameter(@RequestParam("requiredValue") String requiredValue) {
            return Map.of("requiredValue", requiredValue);
        }

        @GetMapping("/auto-configured/annotated-status")
        Map<String, Object> annotatedStatus() {
            throw new AnnotatedConflictException();
        }

        @GetMapping("/auto-configured/classified-headers")
        Map<String, Object> classifiedHeaders() {
            throw new ClassifiedHeaderException();
        }
    }

    @ResponseStatus(code = HttpStatus.CONFLICT, reason = "sensitive annotated reason")
    static final class AnnotatedConflictException extends RuntimeException {
        AnnotatedConflictException() {
            super("internal annotated detail");
        }
    }

    static final class ClassifiedHeaderException extends ErrorResponseException {
        private final HttpHeaders headers = new HttpHeaders();

        ClassifiedHeaderException() {
            super(HttpStatus.METHOD_NOT_ALLOWED);
            headers.add(HttpHeaders.ALLOW, "GET");
            headers.add(RequestIdFilter.REQUEST_ID_HEADER, "forged-request-id");
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }
    }
}
