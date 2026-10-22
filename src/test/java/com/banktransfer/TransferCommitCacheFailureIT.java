package com.banktransfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.banktransfer.dto.TransferResponse;
import com.banktransfer.repository.TransferRepository;
import com.banktransfer.service.IdempotencyService;
import com.banktransfer.service.TransferThrottleService;
import com.banktransfer.support.AbstractIntegrationIT;

/**
 * 커밋 이후 Redis 캐시·쿨다운 실패가 이미 확정된 이체의 HTTP 결과를 바꾸지 않는지 검증한다.
 */
class TransferCommitCacheFailureIT extends AbstractIntegrationIT {

    @SpyBean
    private IdempotencyService idempotencyService;

    @SpyBean
    private TransferThrottleService transferThrottleService;

    @Autowired
    private TransferRepository transferRepository;

    @Test
    @DisplayName("커밋 후 쿨다운 기록 실패에도 201이고 같은 키 재요청은 돈을 다시 움직이지 않는다")
    void postCommitCooldownFailure_stillReturnsCreatedAndReplays() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String source = "SRC-RF-" + suffix;
        String target = "TGT-RF-" + suffix;
        seedAccount(source, new BigDecimal("100000"));
        seedAccount(target, BigDecimal.ZERO);

        doThrow(new IllegalStateException("cooldown redis failed"))
                .when(transferThrottleService)
                .markSuccess(anyString(), anyString());

        String key = "redis-fail-" + suffix;
        BigDecimal amount = new BigDecimal("1000");
        ResponseEntity<TransferResponse> first = postTransfer(source, target, amount, key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long transferId = first.getBody().getTransferId();
        verify(idempotencyService).cacheSuccess(eq(key), anyString(), eq(transferId));
        verify(transferThrottleService).markSuccess(source, target);

        assertThat(transferRepository.countByAccountNumbers(source, target)).isEqualTo(1);
        assertThat(accountRepository.findByAccountNumber(source).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("99000"));

        ResponseEntity<TransferResponse> replay = postTransfer(source, target, amount, key);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().getTransferId()).isEqualTo(transferId);
        assertThat(transferRepository.countByAccountNumbers(source, target)).isEqualTo(1);
        assertThat(accountRepository.findByAccountNumber(source).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("99000"));
        assertThat(accountRepository.findByAccountNumber(target).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("1000"));
    }
}
