package com.xzwl.cloud.smoke;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SmokeController {
    private final CompatibilitySmokeClient client;

    public SmokeController(CompatibilitySmokeClient client) {
        this.client = client;
    }

    @GetMapping("/api/platform/smoke")
    public SmokeResponse smoke() {
        return client.smoke();
    }
}
