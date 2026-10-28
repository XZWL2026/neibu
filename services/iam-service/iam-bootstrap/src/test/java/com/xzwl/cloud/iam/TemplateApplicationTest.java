package com.xzwl.cloud.iam;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class TemplateApplicationTest {

    @Test
    void bootstrapIsTheSpringBootAssemblyRoot() {
        assertNotNull(TemplateApplication.class.getAnnotation(SpringBootApplication.class));
    }
}
