package com.banktransfer.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.banktransfer.model.TransferStatus;

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

    public Long getTransferId() {
        return transferId;
    }

    public void setTransferId(Long transferId) {
        this.transferId = transferId;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public void setStatus(TransferStatus status) {
        this.status = status;
    }

    public LocalDateTime getExecutedAt() {
        return executedAt;
    }

    public void setExecutedAt(LocalDateTime executedAt) {
        this.executedAt = executedAt;
    }

    public String getSourceAccountNumber() {
        return sourceAccountNumber;
    }

    public void setSourceAccountNumber(String sourceAccountNumber) {
        this.sourceAccountNumber = sourceAccountNumber;
    }

    public String getTargetAccountNumber() {
        return targetAccountNumber;
    }

    public void setTargetAccountNumber(String targetAccountNumber) {
        this.targetAccountNumber = targetAccountNumber;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public BigDecimal getSourceBalanceAfter() {
        return sourceBalanceAfter;
    }

    public void setSourceBalanceAfter(BigDecimal sourceBalanceAfter) {
        this.sourceBalanceAfter = sourceBalanceAfter;
    }

    public BigDecimal getTargetBalanceAfter() {
        return targetBalanceAfter;
    }

    public void setTargetBalanceAfter(BigDecimal targetBalanceAfter) {
        this.targetBalanceAfter = targetBalanceAfter;
    }
}

