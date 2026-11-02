package com.example.ledgerApi.domain;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

public class LedgerException extends RuntimeException {
    public record FieldError(String pointer, String detail) {}

    private final HttpStatus status;
    private final String code;
    private final String title;
    private final List<FieldError> errors;

    public LedgerException(HttpStatus status, String code, String title, String detail, String pointer) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
        this.errors = pointer == null ? List.of() : List.of(new FieldError(pointer, detail));
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public String title() { return title; }
    public List<FieldError> errors() { return errors; }
}