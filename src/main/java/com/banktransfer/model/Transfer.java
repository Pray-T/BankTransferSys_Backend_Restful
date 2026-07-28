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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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

    @Setter
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Setter
    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TransferStatus status = TransferStatus.PENDING;

    @Setter
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

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

    /**
     * 이체를 성공 상태로 마킹하고 완료 시각을 기록한다.
     */
    public void markCompleted(LocalDateTime completedAt) {
        this.status = TransferStatus.COMPLETED;
        this.completedAt = completedAt;
    }
}
