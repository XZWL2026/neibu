package com.xzwl.cloud.billing.infrastructure.identity;

import com.xzwl.cloud.iam.api.SessionDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "iam-service", url = "${iam.service-url:http://127.0.0.1:8210}")
public interface IamSessionClient {
  @GetMapping("/api/v1/auth/session")
  SessionDTO read(@RequestHeader("Cookie") String cookie);

  @PostMapping("/internal/v1/iam/session/verify")
  SessionDTO verify(@RequestHeader("Cookie") String cookie, @RequestHeader("X-CSRF-TOKEN") String csrfToken);
}
