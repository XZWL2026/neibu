package com.xzwl.cloud.smoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;

class SmokeControllerTest {
    @Test
    void publicEndpointTransparentlyReturnsTheFeignClientResponse() throws Exception {
        CompatibilitySmokeClient client = mock(CompatibilitySmokeClient.class);
        SmokeResponse response = new SmokeResponse("compatibility-smoke-service", "request-123");
        when(client.smoke()).thenReturn(response);
        SmokeController controller = new SmokeController(client);

        assertThat(controller.smoke()).isSameAs(response);
        verify(client).smoke();

        MockMvcBuilders.standaloneSetup(controller)
                .build()
                .perform(get("/api/platform/smoke"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationName").value("compatibility-smoke-service"))
                .andExpect(jsonPath("$.requestId").value("request-123"));
    }

    @Test
    void feignClientUsesTheServiceNameAndInternalEndpoint() throws Exception {
        FeignClient client = CompatibilitySmokeClient.class.getAnnotation(FeignClient.class);
        assertThat(client).isNotNull();
        assertThat(client.name()).isEqualTo("compatibility-smoke-service");

        Method smoke = CompatibilitySmokeClient.class.getMethod("smoke");
        GetMapping mapping = smoke.getAnnotation(GetMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly("/internal/v1/platform/smoke");
    }

    @Test
    void internalEndpointReturnsTheStableApplicationNameAndANonemptyRequestId() throws Exception {
        InternalSmokeController controller = new InternalSmokeController();

        SmokeResponse response = controller.smoke();
        assertThat(response.applicationName()).isEqualTo("compatibility-smoke-service");
        assertThat(response.requestId()).isNotBlank();

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        mockMvc.perform(get("/internal/v1/platform/smoke"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationName").value("compatibility-smoke-service"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void wireResponseHasExactlyTheFrozenContractFields() {
        assertThat(SmokeResponse.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("applicationName", "requestId");
    }
}
