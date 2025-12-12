package com.banktransfer.exception;

public class NotFoundException extends BusinessException {
    public NotFoundException(String message) {
        super(AppErrorCode.NOT_FOUND, message);
    }
}

