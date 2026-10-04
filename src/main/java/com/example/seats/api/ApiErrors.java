package com.example.seats.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Models.ErrorBody> domain(ApiException e) {
        return error(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<Models.ErrorBody> invalid(Exception e) {
        return error(400, "INVALID_REQUEST", "Request fields, types, or values are invalid.");
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<Models.ErrorBody> dependency(Exception e) {
        log.error("Database operation failed", e);
        return error(503, "SERVICE_UNAVAILABLE", "The booking decision could not be completed. Retry with the same key.");
    }

    public static ResponseEntity<Models.ErrorBody> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(new Models.ErrorBody(code, message, MDC.get("request_id")));
    }
}
