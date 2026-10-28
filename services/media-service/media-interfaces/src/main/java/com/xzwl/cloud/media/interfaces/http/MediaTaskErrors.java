package com.xzwl.cloud.media.interfaces.http;
import com.xzwl.cloud.media.application.task.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes={EnhancementQuoteController.class,MediaTaskController.class,MediaGenerationController.class,PortraitController.class,DepthController.class,ProductSuiteController.class})
public class MediaTaskErrors {
  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ErrorResponse> invalid() { return error(HttpStatus.BAD_REQUEST,"INVALID_MEDIA_REQUEST"); }
  @ExceptionHandler(DefinitiveRejectionException.class)
  ResponseEntity<ErrorResponse> unsupported(DefinitiveRejectionException failure) {
    String code=java.util.Set.of("REFERENCE_METADATA_REQUIRED","REFERENCE_TYPE_MISMATCH","INVALID_REFERENCE_TYPE",
        "REFERENCE_REJECTED","UNSUPPORTED_OMNI_REFERENCE","INVALID_ENHANCEMENT_SOURCE").contains(failure.getMessage())
        ? failure.getMessage() : "UNSUPPORTED_GENERATION_SELECTION";
    return error(HttpStatus.UNPROCESSABLE_ENTITY,code);
  }
  @ExceptionHandler(TaskConflictException.class)
  ResponseEntity<ErrorResponse> conflict() { return error(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT"); }
  @ExceptionHandler(TaskNotFoundException.class)
  ResponseEntity<ErrorResponse> missing() { return error(HttpStatus.NOT_FOUND,"TASK_NOT_FOUND"); }
  @ExceptionHandler(DependencyUnavailableException.class)
  ResponseEntity<ErrorResponse> unavailable() { return error(HttpStatus.SERVICE_UNAVAILABLE,"MEDIA_DEPENDENCY_UNAVAILABLE"); }
  private ResponseEntity<ErrorResponse> error(HttpStatus status,String code) { return ResponseEntity.status(status).body(new ErrorResponse(code)); }
  public record ErrorResponse(String code) {}
}
