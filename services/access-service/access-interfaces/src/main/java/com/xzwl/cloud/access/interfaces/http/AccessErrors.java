package com.xzwl.cloud.access.interfaces.http;
import com.xzwl.cloud.access.application.key.AccessKeys;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestControllerAdvice
public class AccessErrors {
  @ExceptionHandler(com.xzwl.cloud.access.application.identity.SessionVerifier.Rejected.class)
  ResponseEntity<?> identity(com.xzwl.cloud.access.application.identity.SessionVerifier.Rejected failure){
    return ResponseEntity.status(failure.status).body(Map.of("code","IDENTITY_UNAVAILABLE"));
  }
  @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid(){return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST"));}
  @ExceptionHandler(IllegalStateException.class) ResponseEntity<?> conflict(){return ResponseEntity.status(409).body(Map.of("code","KEY_STATE_CONFLICT"));}
  @ExceptionHandler(AccessKeys.Denied.class) ResponseEntity<?> denied(){return ResponseEntity.status(403).body(Map.of("code","KEY_ACCESS_DENIED"));}
}
