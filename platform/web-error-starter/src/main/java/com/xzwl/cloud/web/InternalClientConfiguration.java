package com.xzwl.cloud.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class InternalClientConfiguration {
    @Bean("internalRestClientBuilder")
    @LoadBalanced
    @ConditionalOnProperty(name = "xzwl.discovery.enabled", havingValue = "true")
    RestClient.Builder discoveredInternalRestClientBuilder() {
        return RestClient.builder().requestInterceptor((request, body, execution) -> {
            try {
                return execution.execute(request, body);
            } catch (IllegalStateException unavailable) {
                // LoadBalancer reports an empty registry as IllegalStateException; retain transport error mapping.
                throw new java.io.IOException("Internal service unavailable", unavailable);
            }
        });
    }

    @Bean("internalRestClientBuilder")
    @ConditionalOnProperty(name = "xzwl.discovery.enabled", havingValue = "false", matchIfMissing = true)
    RestClient.Builder fixedInternalRestClientBuilder() {
        return RestClient.builder();
    }

}
