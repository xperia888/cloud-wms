package com.cloudwms.inventory.api;

import com.cloudwms.inventory.InventoryException;
import com.cloudwms.outbound.OutboundModels.OutboundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 统一错误格式：{ "code": "...", "message": "..." } */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InventoryException.class)
    public ResponseEntity<Map<String, String>> inventory(InventoryException e) {
        HttpStatus status = switch (e.getCode()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case INSUFFICIENT_AVAILABLE, INSUFFICIENT_ALLOCATED, LOCATION_BLOCKED, LPN_CONFLICT -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(Map.of("code", e.getCode().name(), "message", e.getMessage()));
    }

    @ExceptionHandler(OutboundException.class)
    public ResponseEntity<Map<String, String>> outbound(OutboundException e) {
        HttpStatus status = switch (e.getCode()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case INVALID_STATE, INSUFFICIENT_STOCK, NO_RATE -> HttpStatus.CONFLICT;
            case CARRIER_ERROR -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(Map.of("code", e.getCode().name(), "message", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b).orElse("invalid request");
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", msg));
    }

    /** 数据库约束兜底（如 LPN 号重复、数量为负） */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> integrity(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "CONSTRAINT_VIOLATION", "message", e.getMostSpecificCause().getMessage()));
    }
}
