package com.banktransfer.dto;

import java.math.BigDecimal;

import com.banktransfer.model.AccountStatus;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AccountResponse {
    private String accountNumber;
    private BigDecimal balance;
    private String currencyCode;
    private AccountStatus status;
}
