package com.banktransfer.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.banktransfer.model.AccountTransaction;
import com.banktransfer.model.TransactionType;

public interface AccountTransactionRepository extends JpaRepository<AccountTransaction, Long> {

    Optional<AccountTransaction> findByTransferIdAndType(Long transferId, TransactionType type);
}

