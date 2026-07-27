package com.banktransfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.banktransfer.dto.TransferResponse;
import com.banktransfer.repository.TransferRepository;
import com.banktransfer.support.AbstractContainerIT;

/**
 * 동시성 정합성 통합 테스트.
 * 쿨다운은 비활성화해 동시 요청이 스로틀에 가로채이지 않도록 한다.
 */
@TestPropertySource(properties = "app.transfer.cooldown-seconds=0")
class TransferConcurrencyIT extends AbstractContainerIT {

    @Autowired
    private TransferRepository transferRepository;

    @Test
    @DisplayName("동일 출금 계좌 동시 이체 시 잔액이 음수가 되지 않는다")
    void concurrentTransfers_doNotOverdraw() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String source = "SRC-OD-" + suffix;
        seedAccount(source, new BigDecimal("10000"));

        int threads = 20;
        BigDecimal amount = new BigDecimal("1000");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String target = "TGT-OD-" + suffix + "-" + i;
            seedAccount(target, BigDecimal.ZERO);
            String key = "idem-od-" + suffix + "-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                ResponseEntity<String> response = postTransferRaw(source, target, amount, key);
                if (response.getStatusCode() == HttpStatus.CREATED) {
                    successCount.incrementAndGet();
                } else {
                    failureCount.incrementAndGet();
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

        BigDecimal finalBalance = accountRepository.findByAccountNumber(source).orElseThrow().getBalance();
        assertThat(successCount.get()).isEqualTo(10);
        assertThat(failureCount.get()).isEqualTo(10);
        assertThat(finalBalance).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("교차 이체(A→B, B→A) 동시 실행 시 데드락 없이 완료된다")
    void crossTransfers_doNotDeadlock() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String accountA = "ACC-A-" + suffix;
        String accountB = "ACC-B-" + suffix;
        seedAccount(accountA, new BigDecimal("50000"));
        seedAccount(accountB, new BigDecimal("50000"));

        int pairs = 10;
        ExecutorService pool = Executors.newFixedThreadPool(pairs * 2);
        CountDownLatch ready = new CountDownLatch(pairs * 2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < pairs; i++) {
            String keyAb = "cross-ab-" + suffix + "-" + i;
            String keyBa = "cross-ba-" + suffix + "-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                ResponseEntity<TransferResponse> response =
                        postTransfer(accountA, accountB, new BigDecimal("1000"), keyAb);
                if (response.getStatusCode() == HttpStatus.CREATED) {
                    successCount.incrementAndGet();
                }
                return null;
            }));
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                ResponseEntity<TransferResponse> response =
                        postTransfer(accountB, accountA, new BigDecimal("1000"), keyBa);
                if (response.getStatusCode() == HttpStatus.CREATED) {
                    successCount.incrementAndGet();
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

        assertThat(successCount.get()).isEqualTo(pairs * 2);
        BigDecimal balanceA = accountRepository.findByAccountNumber(accountA).orElseThrow().getBalance();
        BigDecimal balanceB = accountRepository.findByAccountNumber(accountB).orElseThrow().getBalance();
        assertThat(balanceA).isEqualByComparingTo(new BigDecimal("50000"));
        assertThat(balanceB).isEqualByComparingTo(new BigDecimal("50000"));
        assertThat(transferRepository.count()).isGreaterThanOrEqualTo(pairs * 2L);
    }
}
