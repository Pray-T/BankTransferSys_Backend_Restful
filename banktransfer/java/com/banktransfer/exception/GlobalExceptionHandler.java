package com.banktransfer.exception;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.banktransfer.dto.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class); 

    @ExceptionHandler(MethodArgumentNotValidException.class) 
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) { //ResponseEntity<ErrorResponse>에서 HTTP상태 코드와 에러 응답을 반환함.
        Map<String, Object> details = new LinkedHashMap<>(); //LinkedHashMap을 사용하여 순서가 보장된 맵을 생성함.
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) { //FieldError를 순회하며 각 필드의 이름과 기본 메시지를 맵에 추가함.
            details.put(fe.getField(), fe.getDefaultMessage());
        }
        ErrorResponse body = buildError(AppErrorCode.VALIDATION_ERROR, ex.getMessage(), request.getRequestURI(), details); 
        return new ResponseEntity<>(body, AppErrorCode.VALIDATION_ERROR.getHttpStatus()); 
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest request) {
        log.warn("Optimistic lock failure: {}", ex.getMessage());
        ErrorResponse body = buildError(
            AppErrorCode.OPTIMISTIC_LOCK_ERROR, 
            "잔액이 변경되었습니다, 확인후 재시도 부탁드립니다.", 
            request.getRequestURI(), 
            null
        );
        return new ResponseEntity<>(body, AppErrorCode.OPTIMISTIC_LOCK_ERROR.getHttpStatus());
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex, HttpServletRequest request) {
        AppErrorCode code = ex.getErrorCode();
        ErrorResponse body = buildError(code, ex.getMessage(), request.getRequestURI(), null);
        if (code.getHttpStatus().is5xxServerError()) {
            log.error("Business exception: {}", ex.getMessage(), ex);
        } else if (code.getHttpStatus() == HttpStatus.CONFLICT) {
            log.warn("Business conflict: {}", ex.getMessage());
        } else {
            log.info("Business error: {}", ex.getMessage());
        }
        return new ResponseEntity<>(body, code.getHttpStatus());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex, HttpServletRequest request) { //최후의 보루. 거름망에 안걸린 모든 에러가 여기 걸린다.
        log.error("Unhandled exception", ex);
        ErrorResponse body = buildError(AppErrorCode.INTERNAL_ERROR, AppErrorCode.INTERNAL_ERROR.getDefaultMessage(), request.getRequestURI(), null);
        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ErrorResponse buildError(AppErrorCode code, String message, String path, Map<String, Object> details) {
        ErrorResponse body = new ErrorResponse();
        body.setCode(code.name());
        body.setMessage(message != null ? message : code.getDefaultMessage());
        body.setPath(path);
        body.setTimestamp(LocalDateTime.now());
        body.setDetails(details);
        return body;
    }
}

