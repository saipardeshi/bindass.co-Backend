package com.bindass.backend.exception;

import com.bindass.backend.dto.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Maps every failure to a clean JSON body AND the correct HTTP status.
 * (Previously client mistakes such as malformed JSON, bad paging params or a
 * denied @PreAuthorize all fell through to the 500 catch-all.)
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private ResponseEntity<ApiResponse<Void>> respond(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiResponse.error(message));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoRoute(NoResourceFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, "Resource not found");
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(BadRequestException ex) {
        return respond(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** 401 — not signed in / bad credentials. */
    @ExceptionHandler({UnauthorizedException.class, AuthenticationException.class})
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(Exception ex) {
        return respond(HttpStatus.UNAUTHORIZED, ex instanceof UnauthorizedException
                ? ex.getMessage() : "Please sign in to continue");
    }

    /** 403 — signed in, but not allowed (wrong owner, missing ADMIN role). */
    @ExceptionHandler({ForbiddenException.class, AccessDeniedException.class})
    public ResponseEntity<ApiResponse<Void>> handleForbidden(Exception ex) {
        return respond(HttpStatus.FORBIDDEN, ex instanceof ForbiddenException
                ? ex.getMessage() : "You do not have permission to do that");
    }

    /** 400 — @Valid failures (including nested objects and list items). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> {
                    String field = fe.getField();
                    String shortName = field.contains(".") ? field.substring(field.lastIndexOf('.') + 1) : field;
                    String msg = fe.getDefaultMessage() == null ? "is invalid" : fe.getDefaultMessage();
                    // custom messages are already complete sentences; built-in ones need the field name
                    return Character.isUpperCase(msg.charAt(0)) ? msg : shortName + " " + msg;
                })
                .distinct()
                .collect(Collectors.joining(", "));
        return respond(HttpStatus.BAD_REQUEST, errors.isBlank() ? "Invalid request" : errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraint(ConstraintViolationException ex) {
        return respond(HttpStatus.BAD_REQUEST, ex.getConstraintViolations().stream()
                .map(v -> v.getMessage()).distinct().collect(Collectors.joining(", ")));
    }

    /** 400 — malformed JSON, wrong field types, empty body, etc. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        return respond(HttpStatus.BAD_REQUEST, "Malformed or missing request body");
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadParam(Exception ex) {
        return respond(HttpStatus.BAD_REQUEST, "Invalid or missing request parameter");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported content type — send application/json");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleTooLarge(MaxUploadSizeExceededException ex) {
        return respond(HttpStatus.PAYLOAD_TOO_LARGE, "Upload is too large");
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicate(DuplicateKeyException ex) {
        return respond(HttpStatus.CONFLICT, "That record already exists");
    }

    /** 502 — Razorpay (or another upstream) failed. */
    @ExceptionHandler(PaymentGatewayException.class)
    public ResponseEntity<ApiResponse<Void>> handleGateway(PaymentGatewayException ex) {
        log.error("Payment gateway error", ex);
        return respond(HttpStatus.BAD_GATEWAY, "Payment gateway is unavailable right now. Please try again.");
    }

    /** 503 — database down. */
    @ExceptionHandler({org.springframework.dao.DataAccessException.class, com.mongodb.MongoException.class})
    public ResponseEntity<ApiResponse<Void>> handleDatabaseDown(Exception ex) {
        log.error("Database failure: {}", ex.getMessage());
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable. Please try again shortly.");
    }

    /** 500 — genuinely unexpected. Never leak internals; return a correlation id instead. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        String errorId = UUID.randomUUID().toString();
        log.error("Unhandled exception [errorId={}]", errorId, ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong. Reference ID: " + errorId);
    }
}
