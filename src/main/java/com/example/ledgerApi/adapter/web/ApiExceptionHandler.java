package com.example.ledgerApi.adapter.web;

import com.example.ledgerApi.domain.LedgerException;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(LedgerException.class)
    ResponseEntity<ProblemDetail> ledger(LedgerException e) {
        return problem(e.status(), e.code(), e.title(), e.getMessage(), e.errors());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable() {
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request",
                "Request body is missing or is not valid JSON", List.of());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemDetail> header(MissingRequestHeaderException e) {
        return problem(HttpStatus.BAD_REQUEST, "MISSING_HEADER", "Missing header",
                "Header '" + e.getHeaderName() + "' is required", List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException e) {
        List<LedgerException.FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(err -> new LedgerException.FieldError(err.getField(), err.getDefaultMessage()))
                .toList();
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request",
                "Request failed validation", errors);
    }

    static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String title,
                                                 String detail, List<LedgerException.FieldError> errors) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(title);
        p.setType(URI.create("https://ledger.example/problems/" + code.toLowerCase().replace('_', '-')));
        p.setProperty("code", code);
        p.setProperty("errors", errors);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
    }
}
