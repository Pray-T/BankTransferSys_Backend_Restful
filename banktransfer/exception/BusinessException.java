package com.banktransfer.exception;

public class BusinessException extends RuntimeException {
    private final AppErrorCode errorCode;

    public BusinessException(AppErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AppErrorCode getErrorCode() {
        return errorCode;
    }
}

