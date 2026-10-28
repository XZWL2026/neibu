package com.xzwl.cloud.iam.interfaces.http;

import com.xzwl.cloud.iam.application.account.AuthFailure;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
@Order(-1)
public final class AuthErrorHandler {
  @ExceptionHandler(AuthFailure.class)
  public ResponseEntity<Map<String, String>> failure(AuthFailure e) { return ResponseEntity.status(e.status()).body(Map.of("code", e.getMessage())); }
}
