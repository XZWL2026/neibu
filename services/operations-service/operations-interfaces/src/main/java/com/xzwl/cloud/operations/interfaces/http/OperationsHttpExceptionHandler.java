package com.xzwl.cloud.operations.interfaces.http;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class OperationsHttpExceptionHandler {
  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
    HttpStatus status = error.getMessage() != null && error.getMessage().contains("not found")
        ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
    return ResponseEntity.status(status).body(Map.of("error", message(error)));
  }
  @ExceptionHandler(IllegalStateException.class)
  ResponseEntity<Map<String, String>> conflict(IllegalStateException error) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", message(error)));
  }
  private static String message(RuntimeException error) {
    return error.getMessage() == null ? "request failed" : error.getMessage();
  }
}
