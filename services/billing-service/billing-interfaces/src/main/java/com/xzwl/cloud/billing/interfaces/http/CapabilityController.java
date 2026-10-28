package com.xzwl.cloud.billing.interfaces.http;

import com.xzwl.cloud.billing.api.CapabilityDTO;
import com.xzwl.cloud.billing.application.capability.GetCapabilityQuery;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/capabilities")
public final class CapabilityController {

    @GetMapping("/{capabilityId}")
    public CapabilityDTO getCapability(@PathVariable String capabilityId) {
        GetCapabilityQuery query = new GetCapabilityQuery(capabilityId);
        return new CapabilityDTO(query.capabilityId());
    }
}
