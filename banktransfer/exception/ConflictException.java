package com.banktransfer.exception;

public class ConflictException extends BusinessException {
    public ConflictException(String message) {
        super(AppErrorCode.CONFLICT, message);
    }
}

