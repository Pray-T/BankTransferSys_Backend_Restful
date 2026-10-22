package com.banktransfer.model;

/**
 * MySQL에 커밋되는 멱등성 상태.
 * 비즈니스 실패는 행을 커밋하지 않으므로 FAILED는 두지 않는다.
 */
public enum TransferIdempotencyStatus {
    PENDING,
    SUCCESS
}
