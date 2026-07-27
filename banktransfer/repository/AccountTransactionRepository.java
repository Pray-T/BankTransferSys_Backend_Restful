package com.banktransfer.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.banktransfer.model.AccountTransaction;

public interface AccountTransactionRepository extends JpaRepository<AccountTransaction, Long> {
}

