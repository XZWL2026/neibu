package com.xzwl.cloud.smoke;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalSmokeController {
    private static final String APPLICATION_NAME = "compatibility-smoke-service";

    @GetMapping("/internal/v1/platform/smoke")
    public SmokeResponse smoke() {
        return new SmokeResponse(APPLICATION_NAME, UUID.randomUUID().toString());
    }
}
