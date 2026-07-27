package com.banktransfer.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.banktransfer.dto.TransferRequest;
import com.banktransfer.dto.TransferResponse;
import com.banktransfer.exception.ConflictException;
import com.banktransfer.exception.InProgressException;
import com.banktransfer.exception.NotFoundException;
import com.banktransfer.model.Account;
import com.banktransfer.model.AccountStatus;
import com.banktransfer.model.AccountTransaction;
import com.banktransfer.model.Constants;
import com.banktransfer.model.IdempotencyStatus;
import com.banktransfer.model.TransactionType;
import com.banktransfer.model.Transfer;
import com.banktransfer.model.IdempotencyRecord;
import com.banktransfer.repository.AccountRepository;
import com.banktransfer.repository.AccountTransactionRepository;
import com.banktransfer.repository.TransferRepository;
import com.banktransfer.util.HashUtil;
import com.banktransfer.util.MoneyUtil;

@Service
public class TransferService {
    private static final Logger log = LoggerFactory.getLogger(TransferService.class); 

    private final AccountRepository accountRepository; 
    private final TransferRepository transferRepository; 
    private final AccountTransactionRepository accountTransactionRepository; 
    private final IdempotencyService idempotencyService;
    private final TransferThrottleService transferThrottleService;

    public TransferService(
                        AccountRepository accountRepository, 
                        TransferRepository transferRepository, 
                        AccountTransactionRepository accountTransactionRepository, 
                        IdempotencyService idempotencyService,
                        TransferThrottleService transferThrottleService) { 
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.accountTransactionRepository = accountTransactionRepository;
        this.idempotencyService = idempotencyService;
        this.transferThrottleService = transferThrottleService;
    }

    @Transactional
    public TransferResponse createTransfer(TransferRequest request, String idempotencyKey) {
        validateRequest(request);

        String sourceAccountNumber = request.getSourceAccountNumber();
        String targetAccountNumber = request.getTargetAccountNumber();

        // 해시와 실제 이체에 동일 스케일(KRW 정수, HALF_UP) 적용
        BigDecimal amount = MoneyUtil.scaleKRW(request.getAmount());
        if (amount == null || amount.signum() <= 0) {
            throw new ConflictException("이체 금액은 0보다 커야 합니다.");
        }

        // 1) 멱등성 먼저: COMPLETED 재조회가 쿨다운(429)에 가려지지 않도록 함
        String requestHash = HashUtil.sha256Hex(sourceAccountNumber
                + "|" + targetAccountNumber
                + "|" + amount.toPlainString());

        IdempotencyStartResult start = idempotencyService.beginOrGetExisting(idempotencyKey, "TRANSFER", requestHash);
        IdempotencyRecord idem = start.record();

        Long previousTransferId = idem.getResourceId();
        if (idem.getStatus() == IdempotencyStatus.COMPLETED && previousTransferId != null) {
            Transfer previous = transferRepository.findById(previousTransferId)
                    .orElseThrow(() -> new NotFoundException("이전 이체 결과를 찾을 수 없습니다."));
            return toResponse(previous, previous.getSourceAccount().getBalance(), previous.getTargetAccount().getBalance());
        }

        if (!start.newlyCreated()) {
            // PENDING(처리 중) 또는 FAILED reclaim 실패 → 동일 키 동시 요청 차단
            throw new InProgressException("요청이 처리 중입니다.");
        }

        // 2) 이 요청이 멱등성 키를 소유한 경우에만 쿨다운·이체 진행
        //    성공 확정(COMPLETED/쿨다운)은 DB 커밋 이후로 미뤄 "DB엔 이체가 없는데
        //    Redis엔 COMPLETED"인 불일치를 방지한다(afterCommit). 실패는 롤백 전
        //    finalizeFailure로 FAILED를 기록해 PENDING 누수를 막는다.
        SuccessConfirmation confirmation = new SuccessConfirmation();
        boolean deferredCommitHook = registerSuccessConfirmation(idem, confirmation);
        try {
            transferThrottleService.enforceCooldown(sourceAccountNumber, targetAccountNumber);

            String a = sourceAccountNumber;
            String b = targetAccountNumber;
            String first = a.compareTo(b) <= 0 ? a : b;
            String second = a.compareTo(b) <= 0 ? b : a;

            Account firstAcc = accountRepository.findByAccountNumberForUpdate(first)
                    .orElseThrow(() -> new NotFoundException("계좌를 찾을 수 없습니다: " + first));
            Account secondAcc = accountRepository.findByAccountNumberForUpdate(second)
                    .orElseThrow(() -> new NotFoundException("계좌를 찾을 수 없습니다: " + second));

            Account source = a.equals(first) ? firstAcc : secondAcc;
            Account target = a.equals(first) ? secondAcc : firstAcc;

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

            AccountTransaction debit = new AccountTransaction(
                    source, transfer, TransactionType.DEBIT, amount, newSourceBalance);
            accountTransactionRepository.save(debit);

            AccountTransaction credit = new AccountTransaction(
                    target, transfer, TransactionType.CREDIT, amount, newTargetBalance);
            accountTransactionRepository.save(credit);

            if (deferredCommitHook) {
                // 커밋 성공 후 afterCommit에서 finalizeSuccess/markSuccess가 실행되도록 결과만 기록
                confirmation.markReady(transfer.getId(), source.getAccountNumber(), target.getAccountNumber());
            } else {
                // 트랜잭션 동기화가 비활성인 예외적 상황: 즉시 반영으로 폴백
                idempotencyService.finalizeSuccess(idem, transfer.getId());
                transferThrottleService.markSuccess(source.getAccountNumber(), target.getAccountNumber());
            }

            log.info("이체 완료: {} -> {} 금액={}, 통화={}",
                    source.getAccountNumber(), target.getAccountNumber(), amount, Constants.DEFAULT_CURRENCY_CODE);

            return toResponse(transfer, newSourceBalance, newTargetBalance);
        } catch (RuntimeException ex) {
            idempotencyService.finalizeFailure(idem);
            throw ex;
        }
    }

    /**
     * 이체 성공 확정(멱등성 COMPLETED 기록 + 쿨다운 설정)을 트랜잭션 커밋 이후로 미룬다.
     * DB가 durable하게 커밋된 뒤에만 Redis에 COMPLETED를 반영해, 커밋 실패 시
     * "Redis는 COMPLETED인데 DB엔 이체가 없는" 불일치를 방지한다.
     *
     * @return 커밋 훅 등록 성공 여부. 동기화가 비활성이면 false를 반환해 호출부가 즉시 반영으로 폴백한다.
     */
    private boolean registerSuccessConfirmation(IdempotencyRecord idem, SuccessConfirmation confirmation) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (confirmation.isReady()) {
                    idempotencyService.finalizeSuccess(idem, confirmation.transferId());
                    transferThrottleService.markSuccess(
                            confirmation.sourceAccountNumber(), confirmation.targetAccountNumber());
                }
            }
        });
        return true;
    }

    /**
     * afterCommit 콜백이 참조하는 성공 이체 결과 홀더.
     * markReady 호출 이후에만(=이체 로직이 정상 완료된 경우에만) 커밋 시 Redis 확정이 실행된다.
     */
    private static final class SuccessConfirmation {
        private boolean ready;
        private Long transferId;
        private String sourceAccountNumber;
        private String targetAccountNumber;

        void markReady(Long transferId, String sourceAccountNumber, String targetAccountNumber) {
            this.transferId = transferId;
            this.sourceAccountNumber = sourceAccountNumber;
            this.targetAccountNumber = targetAccountNumber;
            this.ready = true;
        }

        boolean isReady() {
            return ready;
        }

        Long transferId() {
            return transferId;
        }

        String sourceAccountNumber() {
            return sourceAccountNumber;
        }

        String targetAccountNumber() {
            return targetAccountNumber;
        }
    }

    @Transactional(readOnly = true)
    public TransferResponse getTransfer(Long id) {
        java.util.Objects.requireNonNull(id, "id"); 
        Transfer transfer = transferRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("이체를 찾을 수 없습니다: " + id));
        // balances may have changed since; we don't include balances here
        TransferResponse resp = new TransferResponse();
        resp.setTransferId(transfer.getId());
        resp.setStatus(transfer.getStatus());
        resp.setExecutedAt(transfer.getCompletedAt());
        resp.setSourceAccountNumber(transfer.getSourceAccount().getAccountNumber());
        resp.setTargetAccountNumber(transfer.getTargetAccount().getAccountNumber());
        resp.setAmount(transfer.getAmount());
        resp.setCurrencyCode(transfer.getCurrencyCode());
        return resp;
    }

    private void validateRequest(TransferRequest request) {
        if (request.getSourceAccountNumber().equals(request.getTargetAccountNumber())) {
            throw new ConflictException("출금계좌와 입금계좌가 동일할 수 없습니다.");
        }
        if (request.getAmount() == null || request.getAmount().signum() <= 0) {
            throw new ConflictException("이체 금액은 0보다 커야 합니다.");
        }
    }

    private TransferResponse toResponse(Transfer transfer, BigDecimal sourceBalanceAfter, BigDecimal targetBalanceAfter) {
        TransferResponse resp = new TransferResponse();
        resp.setTransferId(transfer.getId());
        resp.setStatus(transfer.getStatus());
        resp.setExecutedAt(transfer.getCompletedAt());
        resp.setSourceAccountNumber(transfer.getSourceAccount().getAccountNumber());
        resp.setTargetAccountNumber(transfer.getTargetAccount().getAccountNumber());
        resp.setAmount(transfer.getAmount());
        resp.setCurrencyCode(transfer.getCurrencyCode());
        resp.setSourceBalanceAfter(sourceBalanceAfter);
        resp.setTargetBalanceAfter(targetBalanceAfter);
        return resp;
    }
}

