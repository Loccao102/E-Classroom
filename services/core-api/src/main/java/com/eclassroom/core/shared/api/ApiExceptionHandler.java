package com.eclassroom.core.shared.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String,Object>> handle(ApiException e, HttpServletRequest request) {
        return ResponseEntity.status(e.status()).body(error(e.code(), e.getMessage(), request, Map.of()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String,Object>> validation(MethodArgumentNotValidException e, HttpServletRequest request) {
        Map<String,String> fields = new LinkedHashMap<>();
        for (FieldError f : e.getBindingResult().getFieldErrors()) fields.put(f.getField(), f.getDefaultMessage());
        return ResponseEntity.badRequest().body(error("VALIDATION_ERROR", "Request validation failed", request, fields));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String,Object>> unknown(Exception e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error("INTERNAL_ERROR", "Unexpected server error", request, Map.of()));
    }
    private Map<String,Object> error(String code, String message, HttpServletRequest req, Object details) {
        Map<String,Object> out = new LinkedHashMap<>(); out.put("timestamp", Instant.now()); out.put("code", code); out.put("message", message);
        out.put("correlationId", String.valueOf(req.getAttribute("correlationId"))); out.put("details", details); return out;
    }
}
