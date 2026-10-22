package com.banktransfer.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.banktransfer.model.TransferIdempotency;

public interface TransferIdempotencyRepository extends JpaRepository<TransferIdempotency, Long> {

    @Query("""
            select idem from TransferIdempotency idem
            left join fetch idem.transfer
            where idem.idempotencyKey = :idempotencyKey
            """)
    Optional<TransferIdempotency> findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);
}
