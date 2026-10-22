package com.banktransfer.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Redis 캐시 값. 재조회의 원본은 {@link TransferIdempotency}이다.
 */
@Getter
@Setter
@NoArgsConstructor
public class IdempotencyRecord {
    private String key;
    private String resourceType; //요청 처리 결과로 생성된 리소스의 타입
    private Long resourceId; //요청 처리 결과로 생성된 리소스의 고유 식별자
    private IdempotencyStatus status; //요청 처리 결과
    private String requestHash;
    private long createdAtEpochMillis; //요청 처리 시작 시간
}
