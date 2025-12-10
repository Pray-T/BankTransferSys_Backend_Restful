package com.banktransfer.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.banktransfer.dto.AccountResponse;
import com.banktransfer.exception.NotFoundException;
import com.banktransfer.model.Account;
import com.banktransfer.repository.AccountRepository;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public AccountResponse getByAccountNumber(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new NotFoundException("계좌를 찾을 수 없습니다: " + accountNumber));
        AccountResponse resp = new AccountResponse();
        resp.setAccountNumber(account.getAccountNumber());
        resp.setBalance(account.getBalance());
        resp.setCurrencyCode(account.getCurrencyCode());
        resp.setStatus(account.getStatus());
        return resp;
    }
}

