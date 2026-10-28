package com.xzwl.cloud.billing.interfaces.http;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestControllerAdvice
public final class BillingErrors {
  @ExceptionHandler(org.springframework.web.client.RestClientException.class)
  ResponseEntity<?> dependency(){return ResponseEntity.status(503).body(Map.of("code","ACCESS_UNAVAILABLE"));}
  @ExceptionHandler(IllegalArgumentException.class)ResponseEntity<?> invalid(){return ResponseEntity.badRequest().body(Map.of("code","INVALID_BILLING_REQUEST"));}
  @ExceptionHandler(IllegalStateException.class)ResponseEntity<?> conflict(){return ResponseEntity.status(409).body(Map.of("code","BILLING_STATE_CONFLICT"));}
}
