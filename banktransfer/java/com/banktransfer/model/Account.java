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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import com.banktransfer.exception.AppErrorCode;
import com.banktransfer.exception.BusinessException;

@Entity 
@Table(
        name = "accounts",
        indexes = {
                @Index(name = "idx_account_number", columnList = "account_number"),
                @Index(name = "idx_account_balance", columnList = "balance")
        },
        uniqueConstraints = { 
                @UniqueConstraint(name = "uq_account_number", columnNames = {"account_number"}) 
        }
)
public class Account extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_number", nullable = false, length = 64)
    private String accountNumber;

    @Column(name = "balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Enumerated(EnumType.STRING) 
    @Column(name = "status", nullable = false, length = 16)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Version 
    @Column(name = "version", nullable = false) 
    private Long version; 

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @PrePersist 
    void onCreate() { 
        if (this.currencyCode == null) { 
            this.currencyCode = Constants.DEFAULT_CURRENCY_CODE; 
        }
        if (this.balance == null) {  
            this.balance = BigDecimal.valueOf(100000).setScale(2);  
        }
    }

    public Long getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }

    public Long getVersion() {
        return version;
    }

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public void deposit(BigDecimal amount) {
        if( amount.compareTo(BigDecimal.ZERO) <= 0 ) {
            throw new BusinessException(AppErrorCode.VALIDATION_ERROR, "입금 금액은 0보다 커야 합니다.");
        }
        this.balance = this.balance.add(amount);
    }

    public void withdraw(BigDecimal amount) {
        if( this.balance.compareTo(amount) < 0 ) {
            throw new BusinessException(AppErrorCode.INSUFFICIENT_BALANCE, "잔액이 부족합니다.");
        }
        this.balance = this.balance.subtract(amount);
    }

}
