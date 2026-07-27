package com.banktransfer.service;

import com.banktransfer.model.IdempotencyRecord;

//IdempotencyStartResult의 record는 요청 처리 결과로 생성된 리소스의 정보, newlyCreated는 요청이 처리 중인지 여부
public record IdempotencyStartResult(IdempotencyRecord record, boolean newlyCreated) { 
}


