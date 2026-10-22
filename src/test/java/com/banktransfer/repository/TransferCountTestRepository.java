package com.banktransfer.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.banktransfer.model.Transfer;

/**
 * 테스트만 호출하는 이체 건수 조회.
 * 프로덕션 {@link TransferRepository}에는 두지 않는다.
 */
public interface TransferCountTestRepository extends Repository<Transfer, Long> {

    @Query("""
            select count(t) from Transfer t
            where t.sourceAccount.accountNumber = :source
              and t.targetAccount.accountNumber = :target
            """)
    long countByAccountNumbers(@Param("source") String source,
                               @Param("target") String target);
}
