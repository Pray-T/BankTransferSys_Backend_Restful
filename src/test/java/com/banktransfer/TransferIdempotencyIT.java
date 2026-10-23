package com.banktransfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.banktransfer.dto.ErrorResponse;
import com.banktransfer.dto.TransferRequest;
import com.banktransfer.dto.TransferResponse;
import com.banktransfer.model.Account;
import com.banktransfer.model.AccountTransaction;
import com.banktransfer.model.TransactionType;
import com.banktransfer.model.Transfer;
import com.banktransfer.model.TransferIdempotencyStatus;
import com.banktransfer.repository.AccountTransactionRepository;
import com.banktransfer.repository.TransferCountTestRepository;
import com.banktransfer.repository.TransferIdempotencyRepository;
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

    @Autowired
    private TransferCountTestRepository transferCountTestRepository;

    @Autowired
    private TransferIdempotencyRepository transferIdempotencyRepository;

    @Autowired
    private AccountTransactionRepository accountTransactionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

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
        assertThat(first.getHeaders().getFirst("Idempotency-Key")).isEqualTo(key);
        Long transferId = first.getBody().getTransferId();

        ResponseEntity<TransferResponse> second = postTransfer(source, target, amount, key);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody().getTransferId()).isEqualTo(transferId);
        assertThat(second.getHeaders().getFirst("Idempotency-Key")).isEqualTo(key);

        BigDecimal sourceBalance = accountRepository.findByAccountNumber(source).orElseThrow().getBalance();
        BigDecimal targetBalance = accountRepository.findByAccountNumber(target).orElseThrow().getBalance();
        assertThat(sourceBalance).isEqualByComparingTo(new BigDecimal("98500"));
        assertThat(targetBalance).isEqualByComparingTo(new BigDecimal("1500"));
        assertThat(countCompletedTransfers(source, target)).isEqualTo(1);
        assertThat(transferIdempotencyRepository.findByIdempotencyKey(key)).get()
                .extracting(row -> row.getStatus(), row -> row.getTransfer().getId())
                .containsExactly(TransferIdempotencyStatus.SUCCESS, transferId);
    }

    @Test
    @DisplayName("대소문자만 다른 Idempotency-Key는 재조회가 아니라 서로 다른 이체다")
    void differentCaseKeys_createDistinctTransfers() throws Exception {
        assertIdempotencyKeyCollation();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String source = "SRC-CS-" + suffix;
        String target = "TGT-CS-" + suffix;
        seedAccount(source, new BigDecimal("100000"));
        seedAccount(target, BigDecimal.ZERO);

        String upperKey = "AbC-" + suffix;
        String lowerKey = "abc-" + suffix;
        BigDecimal amount = new BigDecimal("1000");

        ResponseEntity<TransferResponse> first = postTransfer(source, target, amount, upperKey);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody()).isNotNull();
        assertThat(first.getHeaders().getFirst("Idempotency-Key")).isEqualTo(upperKey);

        stringRedisTemplate.delete(cooldownKey(source, target));

        ResponseEntity<TransferResponse> second = postTransfer(source, target, amount, lowerKey);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().getTransferId()).isNotEqualTo(first.getBody().getTransferId());
        assertThat(second.getHeaders().getFirst("Idempotency-Key")).isEqualTo(lowerKey);

        assertThat(countCompletedTransfers(source, target)).isEqualTo(2);
        assertThat(accountRepository.findByAccountNumber(source).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("98000"));
        assertThat(accountRepository.findByAccountNumber(target).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("2000"));
        assertThat(transferIdempotencyRepository.findByIdempotencyKey(upperKey)).get()
                .extracting(row -> row.getStatus(), row -> row.getTransfer().getId())
                .containsExactly(TransferIdempotencyStatus.SUCCESS, first.getBody().getTransferId());
        assertThat(transferIdempotencyRepository.findByIdempotencyKey(lowerKey)).get()
                .extracting(row -> row.getStatus(), row -> row.getTransfer().getId())
                .containsExactly(TransferIdempotencyStatus.SUCCESS, second.getBody().getTransferId());
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
    @DisplayName("같은 방향의 서로 다른 키를 동시에 보내면 하나만 성공하고 나머지는 429이며 이체는 하나다")
    void concurrentDifferentKeysSameDirection_onlyOnePassesCooldown() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String source = "SRC-RC-" + suffix;
        String target = "TGT-RC-" + suffix;
        seedAccount(source, new BigDecimal("100000"));
        seedAccount(target, BigDecimal.ZERO);

        long completedBefore = countCompletedTransfers(source, target);
        int threads = 10;
        BigDecimal amount = new BigDecimal("1000");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger cooledDown = new AtomicInteger();
        ConcurrentLinkedQueue<String> unexpected = new ConcurrentLinkedQueue<>();
        AtomicReference<String> winnerKey = new AtomicReference<>();
        AtomicReference<Long> winnerTransferId = new AtomicReference<>();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String key = "cd-race-" + suffix + "-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                ResponseEntity<String> response = postTransferRaw(source, target, amount, key);
                if (response.getStatusCode() == HttpStatus.CREATED) {
                    created.incrementAndGet();
                    winnerKey.set(key);
                    winnerTransferId.set(readTransferId(response.getBody()));
                } else if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS
                        && response.getBody() != null
                        && response.getBody().contains("TRANSFER_COOLDOWN")) {
                    cooledDown.incrementAndGet();
                } else {
                    unexpected.add(response.getStatusCode() + " " + response.getBody());
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

        assertThat(unexpected).isEmpty();
        assertThat(created.get()).isEqualTo(1);
        assertThat(cooledDown.get()).isEqualTo(threads - 1);
        assertThat(countCompletedTransfers(source, target)).isEqualTo(completedBefore + 1);
        assertThat(transferCountTestRepository.countByAccountNumbers(source, target)).isEqualTo(completedBefore + 1);
        assertThat(accountRepository.findByAccountNumber(source).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("99000"));
        assertThat(accountRepository.findByAccountNumber(target).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("1000"));

        ResponseEntity<TransferResponse> replay = postTransfer(source, target, amount, winnerKey.get());
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).isNotNull();
        assertThat(replay.getBody().getTransferId()).isEqualTo(winnerTransferId.get());
        assertThat(countCompletedTransfers(source, target)).isEqualTo(completedBefore + 1);
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
        assertThat(transferCountTestRepository.countByAccountNumbers(source, target)).isEqualTo(1);
        assertThat(accountRepository.findByAccountNumber(source).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("99000"));
        assertThat(accountRepository.findByAccountNumber(target).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("1000"));
    }

    @Test
    @DisplayName("Idempotency-Key 생략 또는 공백은 400이고 이체 행을 만들지 않는다")
    void missingOrBlankKey_isRejectedWithoutTransfer() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String source = "SRC-NK-" + suffix;
        String target = "TGT-NK-" + suffix;
        seedAccount(source, new BigDecimal("100000"));
        seedAccount(target, BigDecimal.ZERO);

        ResponseEntity<ErrorResponse> missing = postTransferError(source, target, new BigDecimal("1000"), null);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody().getCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(missing.getHeaders().getFirst("Idempotency-Key")).isNull();

        ResponseEntity<ErrorResponse> blank = postTransferError(source, target, new BigDecimal("1000"), "   ");
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blank.getBody().getCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(blank.getHeaders().getFirst("Idempotency-Key")).isNull();

        assertThat(transferCountTestRepository.countByAccountNumbers(source, target)).isZero();
    }

    @Test
    @DisplayName("ASCII가 아닌 Idempotency-Key는 400이고 이체 행을 만들지 않는다")
    void nonAsciiKey_isRejectedWithoutTransfer() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String source = "SRC-NA-" + suffix;
        String target = "TGT-NA-" + suffix;
        seedAccount(source, new BigDecimal("100000"));
        seedAccount(target, BigDecimal.ZERO);

        String key = "abc-\u00e9-" + suffix;
        ResponseEntity<ErrorResponse> rejected = postTransferError(source, target, new BigDecimal("1000"), key);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejected.getBody()).isNotNull();
        assertThat(rejected.getBody().getCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(rejected.getBody().getMessage()).contains("ASCII");
        // ascii 컬럼과 비ASCII 비교는 collation 오류가 나므로, 이체 행이 없는지로 확인한다.
        assertThat(transferCountTestRepository.countByAccountNumbers(source, target)).isZero();
        assertThat(accountRepository.findByAccountNumber(source).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("100000"));
    }

    @Test
    @DisplayName("커밋 전 실패는 멱등성 행을 롤백하고 같은 키로 다시 이체할 수 있다")
    void failureBeforeCommit_rollsBackAndAllowsSameKey() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String source = "SRC-RB-" + suffix;
        String target = "TGT-RB-" + suffix;
        seedAccount(source, new BigDecimal("500"));
        seedAccount(target, BigDecimal.ZERO);

        String key = "rollback-" + suffix;
        ResponseEntity<ErrorResponse> rejected =
                postTransferError(source, target, new BigDecimal("1000"), key);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rejected.getBody().getCode()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(transferCountTestRepository.countByAccountNumbers(source, target)).isZero();
        assertThat(transferIdempotencyRepository.findByIdempotencyKey(key)).isEmpty();
        assertThat(accountRepository.findByAccountNumber(source).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("500"));

        ResponseEntity<TransferResponse> retried =
                postTransfer(source, target, new BigDecimal("200"), key);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(transferCountTestRepository.countByAccountNumbers(source, target)).isEqualTo(1);
        assertThat(accountRepository.findByAccountNumber(source).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("300"));
        assertThat(accountRepository.findByAccountNumber(target).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("200"));
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
        ConcurrentLinkedQueue<String> unexpected = new ConcurrentLinkedQueue<>();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                ResponseEntity<String> response =
                        postTransferRaw(source, target, new BigDecimal("3000"), key);
                if (response.getStatusCode() == HttpStatus.CREATED) {
                    createdOrReplay.incrementAndGet();
                    transferIds.add(readTransferId(response.getBody()));
                } else if (response.getStatusCode() == HttpStatus.ACCEPTED) {
                    inProgress.incrementAndGet();
                } else {
                    unexpected.add(response.getStatusCode() + " " + response.getBody());
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

        assertThat(unexpected).isEmpty();
        assertThat(createdOrReplay.get() + inProgress.get()).isEqualTo(threads);
        assertThat(createdOrReplay.get()).isGreaterThanOrEqualTo(1);
        assertThat(countCompletedTransfers(source, target)).isEqualTo(1);
        assertThat(transferCountTestRepository.countByAccountNumbers(source, target)).isEqualTo(1);

        ResponseEntity<TransferResponse> replay =
                postTransfer(source, target, new BigDecimal("3000"), key);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        transferIds.add(replay.getBody().getTransferId());
        assertThat(transferIds).hasSize(1);

        BigDecimal sourceBalance = accountRepository.findByAccountNumber(source).orElseThrow().getBalance();
        assertThat(sourceBalance).isEqualByComparingTo(new BigDecimal("97000"));
    }

    @Test
    @DisplayName("첫 응답, 재응답, 조회의 금액 문자열은 같은 정수다")
    void amountText_matchesAcrossCreateReplayAndGet() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String source = "SRC-KRW-" + suffix;
        String target = "TGT-KRW-" + suffix;
        seedAccount(source, new BigDecimal("100000"));
        seedAccount(target, BigDecimal.ZERO);
        assertKrwAmountColumns();

        String key = "krw-scale-" + suffix;
        ResponseEntity<String> first = postTransferRaw(source, target, new BigDecimal("1000"), key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String firstAmount = jsonNumber(first.getBody(), "amount");
        String firstSourceBalance = jsonNumber(first.getBody(), "sourceBalanceAfter");
        String firstTargetBalance = jsonNumber(first.getBody(), "targetBalanceAfter");

        ResponseEntity<String> replay = postTransferRaw(source, target, new BigDecimal("1000"), key);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long transferId = readTransferId(first.getBody());
        ResponseEntity<String> fetched = restTemplate.getForEntity("/api/transfers/" + transferId, String.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(firstAmount).isEqualTo("1000");
        assertThat(jsonNumber(replay.getBody(), "amount")).isEqualTo(firstAmount);
        assertThat(jsonNumber(fetched.getBody(), "amount")).isEqualTo(firstAmount);
        assertThat(jsonNumber(replay.getBody(), "sourceBalanceAfter")).isEqualTo(firstSourceBalance);
        assertThat(jsonNumber(replay.getBody(), "targetBalanceAfter")).isEqualTo(firstTargetBalance);
        assertThat(firstSourceBalance).doesNotContain(".");
        assertThat(firstTargetBalance).doesNotContain(".");
        assertThat(jsonNumber(fetched.getBody(), "sourceBalanceAfter")).isEqualTo(firstSourceBalance);
        assertThat(jsonNumber(fetched.getBody(), "targetBalanceAfter")).isEqualTo(firstTargetBalance);
        assertThat(countCompletedTransfers(source, target)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 이체·같은 유형의 원장을 다시 저장하면 거부되고 두 번째 행은 생기지 않는다")
    void duplicateLedgerPosting_isRejected() throws Exception {
        assertLedgerUniqueIndex();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String source = "SRC-LG-" + suffix;
        String target = "TGT-LG-" + suffix;
        seedAccount(source, new BigDecimal("100000"));
        seedAccount(target, BigDecimal.ZERO);

        ResponseEntity<TransferResponse> created =
                postTransfer(source, target, new BigDecimal("1500"), "ledger-" + suffix);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long transferId = created.getBody().getTransferId();
        assertThat(countLedgerRows(transferId, "DEBIT")).isEqualTo(1);

        Transfer transfer = transferRepository.findById(transferId).orElseThrow();
        Account sourceAccount = accountRepository.findByAccountNumber(source).orElseThrow();
        AccountTransaction duplicate = new AccountTransaction(
                sourceAccount,
                transfer,
                TransactionType.DEBIT,
                new BigDecimal("1500"),
                BigDecimal.ONE);

        assertThatThrownBy(() -> accountTransactionRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countLedgerRows(transferId, "DEBIT")).isEqualTo(1);
        assertThat(countLedgerRows(transferId, "CREDIT")).isEqualTo(1);
        AccountTransaction stored = accountTransactionRepository
                .findByTransferIdAndType(transferId, TransactionType.DEBIT)
                .orElseThrow();
        assertThat(stored.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("98500"));
    }

    @Test
    @DisplayName("이후 잔액이 바뀌어도 같은 키 재응답과 조회는 처음 잔액과 transferId를 돌려준다")
    void replayAfterLaterTransfer_returnsOriginalBalances() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String source = "SRC-BL-" + suffix;
        String target = "TGT-BL-" + suffix;
        seedAccount(source, new BigDecimal("100000"));
        seedAccount(target, BigDecimal.ZERO);

        String key = "balance-replay-" + suffix;
        ResponseEntity<TransferResponse> first =
                postTransfer(source, target, new BigDecimal("1000"), key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody()).isNotNull();
        assertThat(first.getBody().getSourceBalanceAfter()).isEqualByComparingTo(new BigDecimal("99000"));
        assertThat(first.getBody().getTargetBalanceAfter()).isEqualByComparingTo(new BigDecimal("1000"));

        ResponseEntity<TransferResponse> later =
                postTransfer(target, source, new BigDecimal("400"), "later-" + suffix);
        assertThat(later.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(accountRepository.findByAccountNumber(source).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("99400"));
        assertThat(accountRepository.findByAccountNumber(target).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("600"));

        ResponseEntity<TransferResponse> replay = postTransfer(source, target, new BigDecimal("1000"), key);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).isNotNull();
        assertThat(replay.getBody().getTransferId()).isEqualTo(first.getBody().getTransferId());
        assertThat(replay.getBody().getSourceBalanceAfter())
                .isEqualByComparingTo(first.getBody().getSourceBalanceAfter());
        assertThat(replay.getBody().getTargetBalanceAfter())
                .isEqualByComparingTo(first.getBody().getTargetBalanceAfter());
        assertThat(replay.getBody().getSourceBalanceAfter()).isEqualByComparingTo(new BigDecimal("99000"));
        assertThat(replay.getBody().getTargetBalanceAfter()).isEqualByComparingTo(new BigDecimal("1000"));

        ResponseEntity<TransferResponse> fetched = restTemplate.getForEntity(
                "/api/transfers/" + first.getBody().getTransferId(),
                TransferResponse.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().getSourceBalanceAfter()).isEqualByComparingTo(new BigDecimal("99000"));
        assertThat(fetched.getBody().getTargetBalanceAfter()).isEqualByComparingTo(new BigDecimal("1000"));

        assertThat(accountRepository.findByAccountNumber(source).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("99400"));
        assertThat(accountRepository.findByAccountNumber(target).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("600"));
        assertThat(countCompletedTransfers(source, target)).isEqualTo(1);
        assertThat(countCompletedTransfers(target, source)).isEqualTo(1);
    }

    @Test
    @DisplayName("소수, 0, 음수, 자릿수를 넘는 지수 표기는 400이고 이체를 만들지 않는다")
    void abnormalAmount_isRejectedWithoutTransfer() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String source = "SRC-BAD-" + suffix;
        String target = "TGT-BAD-" + suffix;
        seedAccount(source, new BigDecimal("100000"));
        seedAccount(target, BigDecimal.ZERO);

        assertRejectedAmount(source, target, "1000.6", "frac-" + suffix);
        assertRejectedAmount(source, target, "1000.00", "scale-" + suffix);
        assertRejectedAmount(source, target, "0", "zero-" + suffix);
        assertRejectedAmount(source, target, "-5", "neg-" + suffix);
        assertRejectedAmount(source, target, "1e20", "exp-" + suffix);

        assertThat(accountRepository.findByAccountNumber(source).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("100000"));
        assertThat(countCompletedTransfers(source, target)).isZero();
    }

    private void assertRejectedAmount(String source, String target, String amountLiteral, String key) {
        String json = "{\"sourceAccountNumber\":\"" + source
                + "\",\"targetAccountNumber\":\"" + target
                + "\",\"amount\":" + amountLiteral + "}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/transfers",
                HttpMethod.POST,
                new HttpEntity<>(json, headers),
                ErrorResponse.class);
        assertThat(response.getStatusCode()).as(amountLiteral).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("VALIDATION_ERROR");
    }

    private String jsonNumber(String body, String field) throws Exception {
        JsonNode node = objectMapper.readTree(body).get(field);
        assertThat(node).as(field).isNotNull();
        assertThat(node.isNumber()).as(field).isTrue();
        String plain = node.decimalValue().toPlainString();
        assertThat(plain).as(field).doesNotContain(".");
        return plain;
    }

    private void assertKrwAmountColumns() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getCatalog()).endsWith("_test");
            assertThat(connection.getCatalog()).isNotEqualToIgnoringCase("bank_TransferSys");
            assertDecimal19Scale0(connection, "accounts", "balance");
            assertDecimal19Scale0(connection, "transfers", "amount");
            assertDecimal19Scale0(connection, "account_transactions", "amount");
            assertDecimal19Scale0(connection, "account_transactions", "balance_after");
        }
    }

    private void assertIdempotencyKeyCollation() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getCatalog()).isEqualTo("bank_transfer_test");
            assertThat(connection.getCatalog()).isNotEqualToIgnoringCase("bank_TransferSys");
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COLUMN_TYPE, CHARACTER_SET_NAME, COLLATION_NAME, CHARACTER_MAXIMUM_LENGTH "
                            + "FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transfer_idempotency' "
                            + "AND COLUMN_NAME = 'idempotency_key'")) {
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("COLUMN_TYPE")).startsWith("varchar(128)");
                    assertThat(rows.getString("CHARACTER_SET_NAME")).isEqualToIgnoringCase("ascii");
                    assertThat(rows.getString("COLLATION_NAME")).isEqualToIgnoringCase("ascii_bin");
                    assertThat(rows.getInt("CHARACTER_MAXIMUM_LENGTH")).isEqualTo(128);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT NON_UNIQUE FROM information_schema.STATISTICS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transfer_idempotency' "
                            + "AND INDEX_NAME = 'uk_transfer_idempotency_key' "
                            + "AND COLUMN_NAME = 'idempotency_key'")) {
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt("NON_UNIQUE")).isZero();
                }
            }
        }
    }

    private static void assertDecimal19Scale0(Connection connection, String table, String column) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT DATA_TYPE, NUMERIC_PRECISION, NUMERIC_SCALE "
                        + "FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("%s.%s", table, column).isTrue();
                assertThat(rows.getString("DATA_TYPE")).isEqualToIgnoringCase("decimal");
                assertThat(rows.getInt("NUMERIC_PRECISION")).isEqualTo(19);
                assertThat(rows.wasNull()).isFalse();
                int scale = rows.getInt("NUMERIC_SCALE");
                assertThat(rows.wasNull()).isFalse();
                assertThat(scale).isZero();
            }
        }
    }

    private void assertLedgerUniqueIndex() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getCatalog()).isEqualTo("bank_transfer_test");
            assertThat(connection.getCatalog()).isNotEqualToIgnoringCase("bank_TransferSys");
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME "
                            + "FROM information_schema.STATISTICS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'account_transactions' "
                            + "AND INDEX_NAME = ? "
                            + "ORDER BY SEQ_IN_INDEX")) {
                statement.setString(1, AccountTransaction.UNIQUE_TRANSFER_TYPE);
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt("NON_UNIQUE")).isZero();
                    assertThat(rows.getInt("SEQ_IN_INDEX")).isEqualTo(1);
                    assertThat(rows.getString("COLUMN_NAME")).isEqualToIgnoringCase("transfer_id");
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt("NON_UNIQUE")).isZero();
                    assertThat(rows.getInt("SEQ_IN_INDEX")).isEqualTo(2);
                    assertThat(rows.getString("COLUMN_NAME")).isEqualToIgnoringCase("type");
                    assertThat(rows.next()).isFalse();
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.STATISTICS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'account_transactions' "
                            + "AND INDEX_NAME = 'idx_account_tx_transfer'")) {
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isZero();
                }
            }
        }
    }

    private long countLedgerRows(long transferId, String type) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM account_transactions WHERE transfer_id = ? AND type = ?")) {
            assertThat(connection.getCatalog()).isEqualTo("bank_transfer_test");
            statement.setLong(1, transferId);
            statement.setString(2, type);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    private long countCompletedTransfers(String source, String target) {
        return transferCountTestRepository.countCompletedByAccountNumbers(source, target);
    }

    private static String cooldownKey(String source, String target) {
        return "trans:cooldown:" + source.trim().toUpperCase(Locale.ROOT)
                + "->" + target.trim().toUpperCase(Locale.ROOT);
    }

    private Long readTransferId(String body) throws Exception {
        JsonNode node = objectMapper.readTree(body);
        return node.get("transferId").asLong();
    }

    private ResponseEntity<ErrorResponse> postTransferError(String source,
                                                            String target,
                                                            BigDecimal amount,
                                                            String idempotencyKey) {
        TransferRequest body = new TransferRequest();
        body.setSourceAccountNumber(source);
        body.setTargetAccountNumber(target);
        body.setAmount(amount);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return restTemplate.exchange(
                "/api/transfers",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                ErrorResponse.class);
    }
}
