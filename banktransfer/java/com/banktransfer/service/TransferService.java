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
import com.banktransfer.exception.InsufficientBalanceException;
import com.banktransfer.exception.NotFoundException;
import com.banktransfer.model.Account;
import com.banktransfer.model.AccountStatus;
import com.banktransfer.model.AccountTransaction;
import com.banktransfer.model.Constants;
import com.banktransfer.model.TransactionType;
import com.banktransfer.model.Transfer;
import com.banktransfer.model.TransferStatus;
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

    public TransferService(
                        AccountRepository accountRepository, 
                        TransferRepository transferRepository, 
                        AccountTransactionRepository accountTransactionRepository, 
                        IdempotencyService idempotencyService) { 
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.accountTransactionRepository = accountTransactionRepository;
        this.idempotencyService = idempotencyService;
    }

    @Transactional
    public TransferResponse createTransfer(TransferRequest request, String idempotencyKey) {
        validateRequest(request);

        String requestHash = HashUtil.sha256Hex(request.getSourceAccountNumber()
                + "|" + request.getTargetAccountNumber()
                + "|" + request.getAmount().setScale(2).toPlainString());

        IdempotencyRecord idem = idempotencyService.beginOrGetExisting(idempotencyKey, "TRANSFER", requestHash);

        if (idem.getStatus().name().equals("COMPLETED") && idem.getResourceId() != null) {
            // Return previous result
            Transfer previous = transferRepository.findById(idem.getResourceId())
                    .orElseThrow(() -> new NotFoundException("이전 이체 결과를 찾을 수 없습니다."));
            return toResponse(previous, previous.getSourceAccount().getBalance(), previous.getTargetAccount().getBalance());
        }

        String a = request.getSourceAccountNumber();
        String b = request.getTargetAccountNumber();
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

        BigDecimal amount = MoneyUtil.scale2(request.getAmount());
        // 잔액 체크는 Account.withdraw() 내부에서 수행되므로 여기서는 제거 가능하지만,
        // 명시적인 에러 메시지나 빠른 실패를 위해 남겨둘 수도 있습니다.
        // 현재는 Account.withdraw()에 위임하는 것이 깔끔하므로 제거합니다.

        Transfer transfer;
        BigDecimal newSourceBalance;
        BigDecimal newTargetBalance;
        try {
            source.withdraw(amount);
            target.deposit(amount);

            newSourceBalance = source.getBalance();
            newTargetBalance = target.getBalance();

            transfer = new Transfer(
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
        } catch (RuntimeException ex) {
            idempotencyService.finalizeFailure(idem);
            throw ex;
        }

        log.info("이체 완료: {} -> {} 금액={}, 통화={}",
                source.getAccountNumber(), target.getAccountNumber(), amount, Constants.DEFAULT_CURRENCY_CODE);

        return toResponse(transfer, newSourceBalance, newTargetBalance);
    }

    @Transactional(readOnly = true)
    public TransferResponse getTransfer(Long id) {
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

