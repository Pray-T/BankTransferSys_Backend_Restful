package com.banktransfer.exception;

public class InsufficientBalanceException extends BusinessException {
    public InsufficientBalanceException(String message) {
        super(AppErrorCode.INSUFFICIENT_BALANCE, message);
    }
}

