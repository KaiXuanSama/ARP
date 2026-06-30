package com.kaixuan.agentreproxy.controller;

import com.kaixuan.agentreproxy.exception.MissingCredentialException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常 → HTTP 状态码映射
 * <p>
 * 仅放跨 controller 复用的映射；单 controller 私有的（如 DuplicateAccountException → 409）
 * 仍留在原 controller 内
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

 @ExceptionHandler(IllegalArgumentException.class)
 @ResponseStatus(HttpStatus.BAD_REQUEST)
 public Map<String, Object> handleBadRequest(IllegalArgumentException ex) {
 return Map.of("status", "error", "message", ex.getMessage());
 }

    @ExceptionHandler(MissingCredentialException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleMissingCredential(MissingCredentialException ex) {
        return Map.of("status", "error", "message", ex.getMessage());
    }
}
