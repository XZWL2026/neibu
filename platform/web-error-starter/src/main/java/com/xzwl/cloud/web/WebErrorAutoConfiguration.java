package com.xzwl.cloud.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestClient;

@AutoConfiguration
@Import(InternalClientConfiguration.class)
public class WebErrorAutoConfiguration {
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "xzwl.api-key.enabled", havingValue = "true")
    PlatformApiKeyFilter platformApiKeyFilter(
        @Qualifier("internalRestClientBuilder") RestClient.Builder builder,
        @org.springframework.beans.factory.annotation.Value("${xzwl.api-key.access-url}") String baseUrl,
        @org.springframework.beans.factory.annotation.Value("${xzwl.api-key.service-token:}") String token
    ) {
        return new PlatformApiKeyFilter(builder, baseUrl, token);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "xzwl.iam-session.enabled", havingValue = "true")
    BrowserIamSessionFilter browserIamSessionFilter(
        @Qualifier("internalRestClientBuilder") RestClient.Builder builder,
        @org.springframework.beans.factory.annotation.Value("${xzwl.iam-session.base-url}") String baseUrl,
        @org.springframework.beans.factory.annotation.Value("${xzwl.iam-session.path-prefix:/api/v1/}") String pathPrefix
    ) {
        return new BrowserIamSessionFilter(builder,baseUrl,pathPrefix);
    }

    @Bean
    @ConditionalOnMissingBean
    RequestIdFilter requestIdFilter() {
        return new RequestIdFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
