package com.xzwl.cloud.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

@SpringBootTest(properties = {
    "spring.cloud.nacos.discovery.enabled=false",
    "spring.cloud.nacos.config.enabled=false"
})
class CompatibilitySmokeApplicationTest {
    @Autowired
    private Environment environment;

    @Test
    void contextLoadsWithPinnedDependencyMatrix() {
        assertThat(environment.getProperty("spring.application.name"))
                .isEqualTo("compatibility-smoke-service");
        String expectedNacosServer = environment.getProperty("NACOS_SERVER_ADDR", "127.0.0.1:8848");
        assertThat(environment.getProperty("spring.cloud.nacos.config.server-addr"))
                .isEqualTo(expectedNacosServer);
        assertThat(environment.getProperty("spring.cloud.nacos.config.refresh-enabled", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty(
                        "spring.cloud.nacos.config.import-check.enabled", Boolean.class))
                .isFalse();
    }
}
