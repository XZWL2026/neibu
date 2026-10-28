package com.xzwl.cloud.payment.interfaces.http;
import com.xzwl.cloud.payment.application.workflow.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestControllerAdvice
public final class PaymentErrors {
  @ExceptionHandler(org.springframework.web.client.RestClientException.class)
  ResponseEntity<?> dependency(){return ResponseEntity.status(503).body(Map.of("code","PAYMENT_DEPENDENCY_UNAVAILABLE"));}
  @ExceptionHandler(PaymentProvider.ChannelAmountLimit.class)
  ResponseEntity<?> channelLimit(){return ResponseEntity.badRequest().body(Map.of("code","PAYMENT_CHANNEL_AMOUNT_LIMIT"));}
  @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid(){return ResponseEntity.badRequest().body(Map.of("code","INVALID_PAYMENT_REQUEST"));}
  @ExceptionHandler(IllegalStateException.class) ResponseEntity<?> conflict(){return ResponseEntity.status(409).body(Map.of("code","PAYMENT_STATE_CONFLICT"));}
  @ExceptionHandler(PaymentWorkflow.Forbidden.class) ResponseEntity<?> denied(){return ResponseEntity.status(403).body(Map.of("code","FORBIDDEN"));}
  @ExceptionHandler(PaymentProvider.Unavailable.class)ResponseEntity<?> unavailable(){return ResponseEntity.status(503).body(Map.of("code","PAYMENT_PROVIDER_UNAVAILABLE"));}
}
