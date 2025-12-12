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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(
        name = "account_transactions",
        indexes = {
                @Index(name = "idx_account_tx_account", columnList = "account_id"),
                @Index(name = "idx_account_tx_transfer", columnList = "transfer_id")
        }
)
public class AccountTransaction extends BaseTimeEntity {

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

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 2) 
    private BigDecimal balanceAfter; 

    // JPA를 위한 Protected 기본 생성자
    protected AccountTransaction() {}

    // 생성 시점에 모든 필수 값을 초기화하는 생성자
    public AccountTransaction(Account account, Transfer transfer, TransactionType type, BigDecimal amount, BigDecimal balanceAfter) {
        this.account = account;
        this.transfer = transfer;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
    }

    public Long getId() {
        return id;
    }

    public Account getAccount() {
        return account;
    }

    public Transfer getTransfer() {
        return transfer;
    }

    public TransactionType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }
}
