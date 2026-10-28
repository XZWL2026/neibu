package com.xzwl.cloud.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.billing.application.identity.SessionVerifier;
import com.xzwl.cloud.billing.infrastructure.identity.FeignSessionVerifier;
import com.xzwl.cloud.billing.infrastructure.identity.IamSessionClient;
import com.xzwl.cloud.billing.interfaces.http.BrowserSessionFilter;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableFeignClients(clients = IamSessionClient.class)
public class BillingSessionConfiguration {
  @Bean SessionVerifier sessionVerifier(IamSessionClient client) { return new FeignSessionVerifier(client); }
  @Bean BrowserSessionFilter browserSessionFilter(SessionVerifier verifier, ObjectMapper json) {
    return new BrowserSessionFilter(verifier, json);
  }
}
