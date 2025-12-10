package com.banktransfer.exception;

import org.springframework.http.HttpStatus;

public enum AppErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "입력값 오류, 입력값을 확인해주세요."),  
    NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 계좌 입니다, 확인후 재시도 해주세요."), 
    CONFLICT(HttpStatus.CONFLICT, "충돌이 발생했습니다. 요청을 확인해주세요."), 
    INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "잔액이 부족합니다."), 
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "내부 서버 오류가 발생했습니다. 잠시 후 재시도 해주세요."); 

    private final HttpStatus httpStatus;  // HTTP 상태 코드
    private final String defaultMessage;

    AppErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus; 
        this.defaultMessage = defaultMessage; 
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() { 
        return defaultMessage; 
    }
}

