package com.banktransfer.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        //    계좌 검증 실패 등도 finalizeFailure로 PENDING 누수 방지
        boolean finalizedSuccess = false;
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

            idempotencyService.finalizeSuccess(idem, transfer.getId());
            finalizedSuccess = true;
            transferThrottleService.markSuccess(source.getAccountNumber(), target.getAccountNumber());

            log.info("이체 완료: {} -> {} 금액={}, 통화={}",
                    source.getAccountNumber(), target.getAccountNumber(), amount, Constants.DEFAULT_CURRENCY_CODE);

            return toResponse(transfer, newSourceBalance, newTargetBalance);
        } catch (RuntimeException ex) {
            if (!finalizedSuccess) {
                idempotencyService.finalizeFailure(idem);
            }
            throw ex;
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

