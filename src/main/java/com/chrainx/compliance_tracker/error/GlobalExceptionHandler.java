package com.chrainx.compliance_tracker.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

// Covers the one error shape (issue #47) that isn't already a deliberate early return inside a
// controller method: a failed @Valid check on a @RequestBody, which Spring MVC throws as
// MethodArgumentNotValidException during argument binding, before any controller code runs -
// there's nowhere in the controller itself to catch it. Every *deliberate* early-return error
// (401/404/409/429/400 for a specific business rule) still lives at its own call site in the
// relevant controller, each building its own ApiError - this class exists only for the one kind
// of error that arrives as a thrown exception instead.
//
// Deliberately does NOT add a catch-all @ExceptionHandler(Exception.class): Spring's own default
// handling already correctly turns framework-level exceptions like
// MethodArgumentTypeMismatchException (a malformed path variable) and NoHandlerFoundException
// (an unmapped path) into 400/404 respectively - see AuthIntegrationTest's regression tests for
// issue #67, which proved these must reach the real default /error handling, not get
// misclassified. A blanket Exception.class handler here would intercept those before Spring's
// own resolution ever ran (Spring checks @ExceptionHandler methods first), silently turning
// correct 400/404s into an incorrect 500 - a real regression against #67, not a generic
// safety net worth adding without handling those cases explicitly too.
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError("BAD_REQUEST", message));
    }
}
