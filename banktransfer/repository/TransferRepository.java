package com.banktransfer.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.banktransfer.model.Transfer;

public interface TransferRepository extends JpaRepository<Transfer, Long> {
}

