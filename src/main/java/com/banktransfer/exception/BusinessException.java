package com.banktransfer.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final AppErrorCode errorCode;

    public BusinessException(AppErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
