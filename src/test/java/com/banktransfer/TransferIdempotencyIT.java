package com.banktransfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.banktransfer.dto.ErrorResponse;
import com.banktransfer.dto.TransferRequest;
import com.banktransfer.dto.TransferResponse;
import com.banktransfer.repository.TransferRepository;
import com.banktransfer.support.AbstractIntegrationIT;

/**
 * 멱등성·쿨다운 통합 테스트.
 */
@TestPropertySource(properties = {
        "app.idempotency.ttl-seconds=60",
        "app.transfer.cooldown-seconds=10"
})
class TransferIdempotencyIT extends AbstractIntegrationIT {

    @Autowired
    private TransferRepository transferRepository;

    @Test
    @DisplayName("동일 Idempotency-Key 재요청은 이전 결과를 반환하고 이중 이체하지 않는다")
    void sameKeyReplay_returnsPreviousResultWithoutDoubleTransfer() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String source = "SRC-ID-" + suffix;
        String target = "TGT-ID-" + suffix;
        seedAccount(source, new BigDecimal("100000"));
        seedAccount(target, BigDecimal.ZERO);

        String key = "replay-" + suffix;
        BigDecimal amount = new BigDecimal("1500");

        ResponseEntity<TransferResponse> first = postTransfer(source, target, amount, key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long transferId = first.getBody().getTransferId();

        ResponseEntity<TransferResponse> second = postTransfer(source, target, amount, key);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody().getTransferId()).isEqualTo(transferId);

        BigDecimal sourceBalance = accountRepository.findByAccountNumber(source).orElseThrow().getBalance();
        BigDecimal targetBalance = accountRepository.findByAccountNumber(target).orElseThrow().getBalance();
        assertThat(sourceBalance).isEqualByComparingTo(new BigDecimal("98500"));
        assertThat(targetBalance).isEqualByComparingTo(new BigDecimal("1500"));
        assertThat(countCompletedTransfers(source, target)).isEqualTo(1);
    }

    @Test
    @DisplayName("COMPLETED 재조회는 쿨다운(429)에 가려지지 않는다")
    void completedReplay_skipsCooldown() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String source = "SRC-CD-" + suffix;
        String target = "TGT-CD-" + suffix;
        seedAccount(source, new BigDecimal("100000"));
        seedAccount(target, BigDecimal.ZERO);

        String key = "cooldown-skip-" + suffix;
        ResponseEntity<TransferResponse> first =
                postTransfer(source, target, new BigDecimal("1000"), key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<TransferResponse> replay =
                postTransfer(source, target, new BigDecimal("1000"), key);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().getTransferId()).isEqualTo(first.getBody().getTransferId());
    }

    @Test
    @DisplayName("동일 방향 새 키 재요청은 쿨다운으로 429를 받는다")
    void newKeySameDirection_hitsCooldown() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String source = "SRC-CD2-" + suffix;
        String target = "TGT-CD2-" + suffix;
        seedAccount(source, new BigDecimal("100000"));
        seedAccount(target, BigDecimal.ZERO);

        ResponseEntity<TransferResponse> first =
                postTransfer(source, target, new BigDecimal("1000"), "cd-first-" + suffix);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second =
                postTransferRaw(source, target, new BigDecimal("1000"), "cd-second-" + suffix);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("동일 키 + 다른 payload는 409 Conflict")
    void sameKeyDifferentPayload_conflicts() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String source = "SRC-CF-" + suffix;
        String target = "TGT-CF-" + suffix;
        seedAccount(source, new BigDecimal("100000"));
        seedAccount(target, BigDecimal.ZERO);

        String key = "conflict-" + suffix;
        ResponseEntity<TransferResponse> first =
                postTransfer(source, target, new BigDecimal("1000"), key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        TransferRequest differentAmount = new TransferRequest();
        differentAmount.setSourceAccountNumber(source);
        differentAmount.setTargetAccountNumber(target);
        differentAmount.setAmount(new BigDecimal("2000"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);

        ResponseEntity<ErrorResponse> conflict = restTemplate.exchange(
                "/api/transfers",
                HttpMethod.POST,
                new HttpEntity<>(differentAmount, headers),
                ErrorResponse.class);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().getCode()).isEqualTo("CONFLICT");
    }

    @Test
    @DisplayName("동일 키 동시 요청은 이중 이체 없이 하나의 transferId만 남긴다")
    void concurrentSameKey_createsSingleTransfer() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String source = "SRC-CK-" + suffix;
        String target = "TGT-CK-" + suffix;
        seedAccount(source, new BigDecimal("100000"));
        seedAccount(target, BigDecimal.ZERO);

        String key = "concurrent-key-" + suffix;
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<Long> transferIds = ConcurrentHashMap.newKeySet();
        AtomicInteger createdOrReplay = new AtomicInteger();
        AtomicInteger inProgress = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                ResponseEntity<String> response =
                        postTransferRaw(source, target, new BigDecimal("3000"), key);
                if (response.getStatusCode() == HttpStatus.CREATED) {
                    createdOrReplay.incrementAndGet();
                } else if (response.getStatusCode() == HttpStatus.ACCEPTED) {
                    inProgress.incrementAndGet();
                } else {
                    unexpected.incrementAndGet();
                }
                return null;
            }));
        }

        assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(unexpected.get()).isZero();
        assertThat(createdOrReplay.get() + inProgress.get()).isEqualTo(threads);
        assertThat(createdOrReplay.get()).isGreaterThanOrEqualTo(1);
        assertThat(countCompletedTransfers(source, target)).isEqualTo(1);

        ResponseEntity<TransferResponse> replay =
                postTransfer(source, target, new BigDecimal("3000"), key);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        transferIds.add(replay.getBody().getTransferId());
        assertThat(transferIds).hasSize(1);

        BigDecimal sourceBalance = accountRepository.findByAccountNumber(source).orElseThrow().getBalance();
        assertThat(sourceBalance).isEqualByComparingTo(new BigDecimal("97000"));
    }

    private long countCompletedTransfers(String source, String target) {
        return transferRepository.countCompletedByAccountNumbers(source, target);
    }
}
