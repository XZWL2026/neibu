package com.xzwl.cloud.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public final class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    public static final String INTERNAL_ERROR_CODE = "INTERNAL_ERROR";
    public static final String INTERNAL_ERROR_MESSAGE = "Internal server error";

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorEnvelope> handleBusinessException(
        BusinessException exception,
        HttpServletRequest request
    ) {
        ErrorEnvelope envelope = new ErrorEnvelope(
            exception.code(),
            exception.getMessage(),
            requestId(request),
            exception.details()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(envelope);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> handleUnknownException(
        Exception exception,
        HttpServletRequest request
    ) {
        String requestId = requestId(request);
        ResponseEntity<ErrorEnvelope> classifiedResponse = classifiedResponse(
            exception,
            requestId
        );
        if (classifiedResponse != null) {
            return classifiedResponse;
        }

        LOGGER.error("Unhandled exception for requestId={}", requestId, exception);
        ErrorEnvelope envelope = new ErrorEnvelope(
            INTERNAL_ERROR_CODE,
            INTERNAL_ERROR_MESSAGE,
            requestId,
            Map.of()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(envelope);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
        Exception exception,
        Object body,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        ErrorEnvelope envelope = classifiedEnvelope(status, requestId(request));
        return super.handleExceptionInternal(
            exception,
            envelope,
            classifiedHeaders(headers),
            status,
            request
        );
    }

    private static ResponseEntity<ErrorEnvelope> classifiedResponse(
        Exception exception,
        String requestId
    ) {
        Throwable candidate = exception;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (candidate != null && visited.add(candidate)) {
            if (candidate instanceof ErrorResponse errorResponse) {
                HttpStatusCode status = errorResponse.getStatusCode();
                return new ResponseEntity<>(
                    classifiedEnvelope(status, requestId),
                    classifiedHeaders(errorResponse.getHeaders()),
                    status
                );
            }

            ResponseStatus responseStatus = AnnotatedElementUtils.findMergedAnnotation(
                candidate.getClass(),
                ResponseStatus.class
            );
            if (responseStatus != null) {
                HttpStatus status = responseStatus.code();
                return ResponseEntity.status(status)
                    .body(classifiedEnvelope(status, requestId));
            }

            Throwable cause = candidate.getCause();
            candidate = cause == candidate ? null : cause;
        }
        return null;
    }

    private static ErrorEnvelope classifiedEnvelope(HttpStatusCode status, String requestId) {
        HttpStatus knownStatus = HttpStatus.resolve(status.value());
        String code = knownStatus == null ? "HTTP_" + status.value() : knownStatus.name();
        String message = knownStatus == null
            ? "HTTP status " + status.value()
            : knownStatus.getReasonPhrase();
        return new ErrorEnvelope(code, message, requestId, Map.of());
    }

    private static HttpHeaders classifiedHeaders(HttpHeaders headers) {
        HttpHeaders safeHeaders = new HttpHeaders();
        safeHeaders.putAll(headers);
        safeHeaders.remove(RequestIdFilter.REQUEST_ID_HEADER);
        return safeHeaders;
    }

    private static String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
    }

    private static String requestId(WebRequest request) {
        return (String) request.getAttribute(
            RequestIdFilter.REQUEST_ID_ATTRIBUTE,
            RequestAttributes.SCOPE_REQUEST
        );
    }
}
