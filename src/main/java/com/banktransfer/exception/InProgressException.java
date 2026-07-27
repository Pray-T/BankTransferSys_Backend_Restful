package com.banktransfer.exception;

public class InProgressException extends BusinessException {
    public InProgressException(String message) {
        super(AppErrorCode.IN_PROGRESS, message);
    }
}


