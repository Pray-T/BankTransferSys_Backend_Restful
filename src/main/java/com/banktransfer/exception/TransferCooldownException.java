package com.banktransfer.exception;

public class TransferCooldownException extends BusinessException {
    public TransferCooldownException(String message) {
        super(AppErrorCode.TRANSFER_COOLDOWN, message);
    }
}


