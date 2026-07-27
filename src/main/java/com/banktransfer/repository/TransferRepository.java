package com.banktransfer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.banktransfer.model.Transfer;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    @Query("""
            select count(t) from Transfer t
            where t.sourceAccount.accountNumber = :source
              and t.targetAccount.accountNumber = :target
              and t.status = com.banktransfer.model.TransferStatus.COMPLETED
            """)
    long countCompletedByAccountNumbers(@Param("source") String source,
                                        @Param("target") String target);
}
