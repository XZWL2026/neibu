package com.xzwl.cloud.canvas.interfaces;

import com.xzwl.cloud.web.ErrorEnvelope;
import com.xzwl.cloud.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = CanvasController.class)
@Order(-10)
public final class CanvasExceptionAdvice {
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorEnvelope> invalid(IllegalArgumentException failure, HttpServletRequest request) {
    return response(HttpStatus.BAD_REQUEST, request);
  }
  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ErrorEnvelope> conflict(IllegalStateException failure, HttpServletRequest request) {
    return response(HttpStatus.CONFLICT, request);
  }
  private ResponseEntity<ErrorEnvelope> response(HttpStatus status, HttpServletRequest request) {
    return ResponseEntity.status(status).body(new ErrorEnvelope(status.name(), status.getReasonPhrase(),
        (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE), Map.of()));
  }
}
