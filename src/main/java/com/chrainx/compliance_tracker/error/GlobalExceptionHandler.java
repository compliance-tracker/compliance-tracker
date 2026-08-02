package com.chrainx.compliance_tracker.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

// Covers error shapes that aren't already a deliberate early return inside a controller method -
// exceptions Spring MVC throws during argument binding, before any controller code runs, so
// there's nowhere in the controller itself to catch them. Every *deliberate* early-return error
// (401/404/409/429/400 for a specific business rule) still lives at its own call site in the
// relevant controller, each building its own ApiError - this class exists only for errors that
// arrive as a thrown exception instead.
//
// Deliberately does NOT add a catch-all @ExceptionHandler(Exception.class): Spring's own default
// handling already correctly turns framework-level exceptions like
// MethodArgumentTypeMismatchException (a malformed path variable) and NoHandlerFoundException
// (an unmapped path) into 400/404 respectively - see AuthIntegrationTest's regression tests for
// issue #67, which proved these must reach the real default /error handling, not get
// misclassified. A blanket Exception.class handler here would intercept those before Spring's
// own resolution ever ran (Spring checks @ExceptionHandler methods first), silently turning
// correct 400/404s into an incorrect 500 - a real regression against #67, not a generic
// safety net worth adding without handling those cases explicitly too. Each handler below is
// scoped to one specific, deliberately-chosen exception type, not a wider net.
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError("BAD_REQUEST", message));
    }

    // Found live, not by inspection: a genuinely malformed request body (invalid JSON, or a
    // literal that can't parse into its target type - e.g. "not-a-date" for a LocalDate field)
    // throws HttpMessageNotReadableException during argument binding, which fell straight
    // through to Spring Boot's own default /error handling - a completely different response
    // shape from this app's own {error, message} ApiError convention, and one that leaks the
    // real exception's message (including internal class/field names) straight to the client.
    // The message here is deliberately generic, not ex.getMessage() - the point of catching this
    // at all is to stop exactly that kind of internal detail from reaching a caller.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleMalformedBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("BAD_REQUEST", "Malformed request body."));
    }
}
