package com.banktransfer.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.banktransfer.model.TransferStatus;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class TransferResponse {

    private Long transferId; // 이체 고유 식별자
    private TransferStatus status;  // 이체 상태
    private LocalDateTime executedAt; // 이체 실행 시간
    private String sourceAccountNumber; // 출금 계좌 번호
    private String targetAccountNumber; // 입금 계좌 번호
    private BigDecimal amount; // 이체 금액
    private String currencyCode; // 통화 코드
    private BigDecimal sourceBalanceAfter; // 출금 후 잔액
    private BigDecimal targetBalanceAfter; // 입금 후 잔액
}
