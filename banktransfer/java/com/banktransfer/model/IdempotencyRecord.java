package com.banktransfer.model;

public class IdempotencyRecord {
    private String key;
    private String resourceType; //요청 처리 결과로 생성된 리소스의 타입
    private Long resourceId; //요청 처리 결과로 생성된 리소스의 고유 식별자
    private IdempotencyStatus status; //요청 처리 결과
    private String requestHash;
    private long createdAtEpochMillis; //요청 처리 시작 시간

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public void setResourceId(Long resourceId) {
        this.resourceId = resourceId;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public void setStatus(IdempotencyStatus status) {
        this.status = status;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public long getCreatedAtEpochMillis() {
        return createdAtEpochMillis;
    }

    public void setCreatedAtEpochMillis(long createdAtEpochMillis) {
        this.createdAtEpochMillis = createdAtEpochMillis;
    }
}

