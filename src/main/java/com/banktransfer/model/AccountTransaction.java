package com.banktransfer.model;

import java.math.BigDecimal;

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
import jakarta.persistence.UniqueConstraint;

import com.banktransfer.util.MoneyUtil;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 한 이체의 출금·입금은 유형별로 한 행이다.
 * {@code (transfer_id, type)} 유니크의 선행 컬럼이 {@code transfer_id}라서
 * {@code transfer_id}만 보던 인덱스는 두지 않는다.
 * 계좌 아이디로 원장을 조회하는 프로덕션 쿼리가 없어 {@code account_id} 인덱스는 두지 않는다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
        name = "account_transactions",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = AccountTransaction.UNIQUE_TRANSFER_TYPE,
                        columnNames = {"transfer_id", "type"})
        }
)
public class AccountTransaction extends BaseTimeEntity {

    public static final String UNIQUE_TRANSFER_TYPE = "uk_account_tx_transfer_type";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_id", nullable = false)
    private Transfer transfer;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private TransactionType type;

    @Column(name = "amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 0)
    private BigDecimal balanceAfter;

    public AccountTransaction(Account account, Transfer transfer, TransactionType type, BigDecimal amount, BigDecimal balanceAfter) {
        this.account = account;
        this.transfer = transfer;
        this.type = type;
        this.amount = MoneyUtil.responseAmount(amount);
        this.balanceAfter = MoneyUtil.responseAmount(balanceAfter);
    }
}
