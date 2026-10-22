package com.banktransfer.service;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.hibernate.JDBCException;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import com.banktransfer.dto.TransferRequest;
import com.banktransfer.dto.TransferResponse;
import com.banktransfer.exception.AppErrorCode;
import com.banktransfer.exception.BusinessException;
import com.banktransfer.exception.ConflictException;
import com.banktransfer.exception.InProgressException;
import com.banktransfer.exception.NotFoundException;
import com.banktransfer.model.Account;
import com.banktransfer.model.AccountStatus;
import com.banktransfer.model.AccountTransaction;
import com.banktransfer.model.Constants;
import com.banktransfer.model.TransactionType;
import com.banktransfer.model.Transfer;
import com.banktransfer.model.TransferIdempotency;
import com.banktransfer.model.TransferIdempotencyStatus;
import com.banktransfer.repository.AccountRepository;
import com.banktransfer.repository.AccountTransactionRepository;
import com.banktransfer.repository.TransferIdempotencyRepository;
import com.banktransfer.repository.TransferRepository;
import com.banktransfer.util.HashUtil;
import com.banktransfer.util.MoneyUtil;

@Service
public class TransferService {
    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final int MAX_IDEMPOTENCY_LOCK_ATTEMPTS = 3;
    private static final int MYSQL_DUP_ENTRY = 1062;
    private static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;
    private static final int MYSQL_DEADLOCK = 1213;

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final AccountTransactionRepository accountTransactionRepository;
    private final TransferIdempotencyRepository transferIdempotencyRepository;
    private final IdempotencyService idempotencyService;
    private final TransferThrottleService transferThrottleService;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public TransferService(
            AccountRepository accountRepository,
            TransferRepository transferRepository,
            AccountTransactionRepository accountTransactionRepository,
            TransferIdempotencyRepository transferIdempotencyRepository,
            IdempotencyService idempotencyService,
            TransferThrottleService transferThrottleService,
            PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.accountTransactionRepository = accountTransactionRepository;
        this.transferIdempotencyRepository = transferIdempotencyRepository;
        this.idempotencyService = idempotencyService;
        this.transferThrottleService = transferThrottleService;
        // REQUIRED(기본값). 멱등성 INSERT와 SUCCESS 갱신, 이체는 한 트랜잭션이다.
        // REQUIRES_NEW를 쓰지 않으므로 롤백 시 이체와 멱등성 행이 함께 사라진다.
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    public TransferResponse createTransfer(TransferRequest request, String idempotencyKey) {
        String key = requireIdempotencyKey(idempotencyKey);
        validateRequest(request);

        BigDecimal amount = MoneyUtil.scaleKRW(request.getAmount());
        if (amount == null || amount.signum() <= 0) {
            throw new ConflictException("이체 금액은 0보다 커야 합니다.");
        }

        String requestHash = requestHash(
                request.getSourceAccountNumber(),
                request.getTargetAccountNumber(),
                amount);

        RuntimeException lastLockFailure = null;
        for (int attempt = 1; attempt <= MAX_IDEMPOTENCY_LOCK_ATTEMPTS; attempt++) {
            Optional<TransferResponse> existing = findCommittedReplay(key, requestHash);
            if (existing.isPresent()) {
                return existing.get();
            }
            try {
                return writeTransaction.execute(status -> performTransfer(request, key, requestHash, amount));
            } catch (RuntimeException ex) {
                if (isIdempotencyDuplicate(ex)) {
                    log.debug("멱등성 키 중복으로 커밋된 결과를 재조회합니다. key={}", key);
                    return requireReplay(key, requestHash);
                }
                if (isIdempotencyLockFailure(ex)) {
                    lastLockFailure = ex;
                    log.warn("멱등성 키 저장이 락 대기로 지연되었습니다. key={}, attempt={}", key, attempt);
                    continue;
                }
                throw ex;
            }
        }
        log.warn("멱등성 키 락 대기 한도를 초과했습니다. key={}", key, lastLockFailure);
        return requireReplay(key, requestHash);
    }

    /**
     * 멱등성 행을 먼저 넣어 유니크 인덱스를 잡은 뒤 이체한다.
     * 비즈니스 예외는 이 트랜잭션을 롤백해 PENDING 행을 남기지 않는다.
     */
    private TransferResponse performTransfer(TransferRequest request,
                                            String idempotencyKey,
                                            String requestHash,
                                            BigDecimal amount) {
        String sourceAccountNumber = request.getSourceAccountNumber();
        String targetAccountNumber = request.getTargetAccountNumber();

        TransferIdempotency idempotency = transferIdempotencyRepository.saveAndFlush(
                TransferIdempotency.pending(idempotencyKey, requestHash));

        transferThrottleService.enforceCooldown(sourceAccountNumber, targetAccountNumber);

        String first = sourceAccountNumber.compareTo(targetAccountNumber) <= 0
                ? sourceAccountNumber : targetAccountNumber;
        String second = sourceAccountNumber.compareTo(targetAccountNumber) <= 0
                ? targetAccountNumber : sourceAccountNumber;

        Account firstAcc = accountRepository.findByAccountNumberForUpdate(first)
                .orElseThrow(() -> new NotFoundException("계좌를 찾을 수 없습니다: " + first));
        Account secondAcc = accountRepository.findByAccountNumberForUpdate(second)
                .orElseThrow(() -> new NotFoundException("계좌를 찾을 수 없습니다: " + second));

        Account source = sourceAccountNumber.equals(first) ? firstAcc : secondAcc;
        Account target = sourceAccountNumber.equals(first) ? secondAcc : firstAcc;

        if (source.getStatus() != AccountStatus.ACTIVE || target.getStatus() != AccountStatus.ACTIVE) {
            throw new ConflictException("비활성화된 계좌 상태입니다.");
        }
        if (!Constants.DEFAULT_CURRENCY_CODE.equals(source.getCurrencyCode())
                || !Constants.DEFAULT_CURRENCY_CODE.equals(target.getCurrencyCode())) {
            throw new ConflictException("지원되지 않는 통화 코드입니다.");
        }

        source.withdraw(amount);
        target.deposit(amount);

        BigDecimal newSourceBalance = source.getBalance();
        BigDecimal newTargetBalance = target.getBalance();

        Transfer transfer = new Transfer(
                source,
                target,
                amount,
                Constants.DEFAULT_CURRENCY_CODE);
        transfer.markCompleted(LocalDateTime.now());
        transfer = transferRepository.save(transfer);

        accountTransactionRepository.save(new AccountTransaction(
                source, transfer, TransactionType.DEBIT, amount, newSourceBalance));
        accountTransactionRepository.save(new AccountTransaction(
                target, transfer, TransactionType.CREDIT, amount, newTargetBalance));

        idempotency.markSuccess(transfer);
        registerPostCommitCache(idempotencyKey, requestHash, transfer.getId(), sourceAccountNumber, targetAccountNumber);

        log.info("이체 완료: {} -> {} 금액={}, 통화={}",
                source.getAccountNumber(), target.getAccountNumber(), amount, Constants.DEFAULT_CURRENCY_CODE);

        return toResponse(transfer, newSourceBalance, newTargetBalance);
    }

    /**
     * Redis 캐시와 쿨다운은 커밋 이후에만 기록한다. 여기서의 예외는 응답을 바꾸지 않는다.
     */
    private void registerPostCommitCache(String idempotencyKey,
                                        String requestHash,
                                        Long transferId,
                                        String sourceAccountNumber,
                                        String targetAccountNumber) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.error("트랜잭션 동기화가 비활성입니다. Redis 캐시와 쿨다운을 건너뜁니다. key={}, transferId={}",
                    idempotencyKey, transferId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    idempotencyService.cacheSuccess(idempotencyKey, requestHash, transferId);
                } catch (Exception ex) {
                    log.error("커밋된 이체의 Redis 멱등성 캐시 기록에 실패했습니다. key={}, transferId={}",
                            idempotencyKey, transferId, ex);
                }
                try {
                    transferThrottleService.markSuccess(sourceAccountNumber, targetAccountNumber);
                } catch (Exception ex) {
                    log.error("커밋된 이체의 쿨다운 기록에 실패했습니다. key={}, transferId={}",
                            idempotencyKey, transferId, ex);
                }
            }
        });
    }

    private Optional<TransferResponse> findCommittedReplay(String idempotencyKey, String requestHash) {
        Optional<TransferResponse> found = readTransaction.execute(status ->
                transferIdempotencyRepository.findByIdempotencyKey(idempotencyKey)
                        .map(row -> toReplayResponse(row, requestHash)));
        return found == null ? Optional.empty() : found;
    }

    private TransferResponse requireReplay(String idempotencyKey, String requestHash) {
        return findCommittedReplay(idempotencyKey, requestHash)
                .orElseThrow(() -> new InProgressException("요청이 처리 중입니다."));
    }

    private TransferResponse toReplayResponse(TransferIdempotency row, String requestHash) {
        if (!row.getRequestHash().equals(requestHash)) {
            throw new ConflictException("Idempotency-Key 재사용이 다른 요청 페이로드와 충돌합니다.");
        }
        if (row.getStatus() != TransferIdempotencyStatus.SUCCESS || row.getTransfer() == null) {
            throw new InProgressException("요청이 처리 중입니다.");
        }
        return toStoredResponse(row.getTransfer());
    }

    /**
     * 재응답과 조회는 계좌의 현재 잔액이 아니라, 커밋 때 원장에 남긴 balanceAfter를 쓴다.
     * 출금은 DEBIT, 입금은 CREDIT. 원장이 없으면 데이터 불일치이므로 404가 아니라 500이다.
     */
    private TransferResponse toStoredResponse(Transfer transfer) {
        Long transferId = transfer.getId();
        return toResponse(
                transfer,
                ledgerBalanceAfter(transferId, TransactionType.DEBIT),
                ledgerBalanceAfter(transferId, TransactionType.CREDIT));
    }

    private BigDecimal ledgerBalanceAfter(Long transferId, TransactionType type) {
        return accountTransactionRepository.findByTransferIdAndType(transferId, type)
                .map(AccountTransaction::getBalanceAfter)
                .orElseThrow(() -> missingLedger(transferId, type));
    }

    private BusinessException missingLedger(Long transferId, TransactionType type) {
        log.error("이체 원장이 없어 이체 시점 잔액을 복원할 수 없습니다. transferId={}, type={}", transferId, type);
        return new BusinessException(
                AppErrorCode.INTERNAL_ERROR,
                AppErrorCode.INTERNAL_ERROR.getDefaultMessage());
    }

    private String requireIdempotencyKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new BusinessException(AppErrorCode.VALIDATION_ERROR, "Idempotency-Key 헤더가 필요합니다.");
        }
        String key = idempotencyKey.trim();
        if (key.length() > Constants.MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BusinessException(
                    AppErrorCode.VALIDATION_ERROR,
                    "Idempotency-Key는 " + Constants.MAX_IDEMPOTENCY_KEY_LENGTH + "자를 초과할 수 없습니다.");
        }
        if (!isAscii(key)) {
            throw new BusinessException(
                    AppErrorCode.VALIDATION_ERROR,
                    "Idempotency-Key는 ASCII 문자만 사용할 수 있습니다.");
        }
        return key;
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    @Transactional(readOnly = true)
    public TransferResponse getTransfer(Long id) {
        java.util.Objects.requireNonNull(id, "id");
        Transfer transfer = transferRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("이체를 찾을 수 없습니다: " + id));
        return toStoredResponse(transfer);
    }

    private void validateRequest(TransferRequest request) {
        if (request.getSourceAccountNumber().equals(request.getTargetAccountNumber())) {
            throw new ConflictException("출금계좌와 입금계좌가 동일할 수 없습니다.");
        }
        if (request.getAmount() == null || request.getAmount().signum() <= 0) {
            throw new ConflictException("이체 금액은 0보다 커야 합니다.");
        }
    }

    private static String requestHash(String sourceAccountNumber, String targetAccountNumber, BigDecimal amount) {
        return HashUtil.sha256Hex(sourceAccountNumber
                + "|" + targetAccountNumber
                + "|" + MoneyUtil.plain(amount));
    }

    private TransferResponse toResponse(Transfer transfer, BigDecimal sourceBalanceAfter, BigDecimal targetBalanceAfter) {
        return TransferResponse.of(
                transfer.getId(),
                transfer.getStatus(),
                transfer.getCompletedAt(),
                transfer.getSourceAccount().getAccountNumber(),
                transfer.getTargetAccount().getAccountNumber(),
                transfer.getAmount(),
                transfer.getCurrencyCode(),
                sourceBalanceAfter,
                targetBalanceAfter);
    }

    private static boolean isIdempotencyDuplicate(Throwable ex) {
        return chainMatches(ex, TransferService::indicatesDuplicateKey)
                && chainMatches(ex, TransferService::throwableMentionsIdempotency);
    }

    private static boolean isIdempotencyLockFailure(Throwable ex) {
        return chainMatches(ex, TransferService::indicatesLockFailure)
                && chainMatches(ex, TransferService::throwableMentionsIdempotency);
    }

    private static boolean indicatesDuplicateKey(Throwable current) {
        if (current instanceof SQLIntegrityConstraintViolationException sql && sql.getErrorCode() == MYSQL_DUP_ENTRY) {
            return true;
        }
        if (current instanceof DataIntegrityViolationException && throwableMentionsIdempotency(current)) {
            return true;
        }
        if (current instanceof ConstraintViolationException cve && textMentionsIdempotency(cve.getConstraintName())) {
            return true;
        }
        String message = current.getMessage();
        return message != null && message.contains("Duplicate entry");
    }

    private static boolean indicatesLockFailure(Throwable current) {
        if (current instanceof PessimisticLockingFailureException) {
            return true;
        }
        if (current instanceof SQLException sql) {
            int errorCode = sql.getErrorCode();
            if (errorCode == MYSQL_LOCK_WAIT_TIMEOUT || errorCode == MYSQL_DEADLOCK) {
                return true;
            }
        }
        String message = current.getMessage();
        return message != null
                && (message.contains("Lock wait timeout") || message.contains("Deadlock found"));
    }

    private static boolean throwableMentionsIdempotency(Throwable current) {
        if (textMentionsIdempotency(current.getMessage())) {
            return true;
        }
        if (current instanceof ConstraintViolationException cve && textMentionsIdempotency(cve.getConstraintName())) {
            return true;
        }
        if (current instanceof JDBCException jdbc && textMentionsIdempotency(jdbc.getSQL())) {
            return true;
        }
        return false;
    }

    private static boolean textMentionsIdempotency(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("transfer_idempotency")
                || lower.contains(TransferIdempotency.UNIQUE_KEY_CONSTRAINT);
    }

    private static boolean chainMatches(Throwable ex, java.util.function.Predicate<Throwable> predicate) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        return walk(ex, predicate, seen);
    }

    private static boolean walk(Throwable ex,
                               java.util.function.Predicate<Throwable> predicate,
                               Set<Throwable> seen) {
        if (ex == null || !seen.add(ex)) {
            return false;
        }
        if (predicate.test(ex)) {
            return true;
        }
        if (walk(ex.getCause(), predicate, seen)) {
            return true;
        }
        for (Throwable suppressed : ex.getSuppressed()) {
            if (walk(suppressed, predicate, seen)) {
                return true;
            }
        }
        return false;
    }
}
