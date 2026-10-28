package com.xzwl.cloud.smoke;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

@FeignClient(name = "compatibility-smoke-service")
public interface CompatibilitySmokeClient {
    @GetMapping("/internal/v1/platform/smoke")
    SmokeResponse smoke();
}
