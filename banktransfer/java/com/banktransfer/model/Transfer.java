package com.banktransfer.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Index;

@Entity
@Table(
        name = "transfers",
        indexes = {
                @Index(name = "idx_transfer_created_at", columnList = "created_at") //최신순 조회가 많기에 created_at 인덱스 추가
        }
)
public class Transfer extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_account_id", nullable = false)
    private Account sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_account_id", nullable = false)
    private Account targetAccount;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TransferStatus status = TransferStatus.PENDING;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    // JPA를 위한 기본 생성자
    protected Transfer() {
    }

    // 계좌 및 금액/통화 정보를 생성 시점에 고정하기 위한 생성자
    public Transfer(Account sourceAccount,
                    Account targetAccount,
                    BigDecimal amount,
                    String currencyCode) {
        this.sourceAccount = sourceAccount;
        this.targetAccount = targetAccount;
        this.amount = amount;
        this.currencyCode = currencyCode;
        this.status = TransferStatus.PENDING;
    }

    public Long getId() {
        return id;
    }

    public Account getSourceAccount() {
        return sourceAccount;
    }

    public Account getTargetAccount() {
        return targetAccount;
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

    public TransferStatus getStatus() {
        return status;
    }

    /**
     * 이체를 성공 상태로 마킹하고 완료 시각을 기록한다.
     */
    public void markCompleted(LocalDateTime completedAt) {
        this.status = TransferStatus.COMPLETED;
        this.completedAt = completedAt;
        this.failureReason = null;
    }

    /**
     * 이체를 실패 상태로 마킹하고 실패 사유와 완료 시각을 기록한다.
     */
    public void markFailed(String failureReason, LocalDateTime completedAt) {
        this.status = TransferStatus.FAILED;
        this.failureReason = failureReason;
        this.completedAt = completedAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }
}

