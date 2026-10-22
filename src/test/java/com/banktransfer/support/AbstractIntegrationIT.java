package com.banktransfer.support;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import com.banktransfer.dto.TransferRequest;
import com.banktransfer.dto.TransferResponse;
import com.banktransfer.model.Account;
import com.banktransfer.model.AccountStatus;
import com.banktransfer.model.Constants;
import com.banktransfer.repository.AccountRepository;

/**
 * 로컬 MySQL / Redis를 사용하는 통합 테스트 기반 클래스.
 * 스키마는 {@code TEST_DB_NAME}(기본 {@code bank_transfer_test})만 사용하고 {@code DB_NAME}은 읽지 않는다.
 * {@link TestDatabaseSafetyInitializer}가 Hibernate DDL 전에 개발 DB 접속을 막는다.
 * 계정은 환경 변수 {@code DB_USERNAME}/{@code DB_PASSWORD}를 따른다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestDatabaseSafetyInitializer.class)
public abstract class AbstractIntegrationIT {

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected AccountRepository accountRepository;

    protected Account seedAccount(String accountNumber, BigDecimal balance) {
        Account account = new Account();
        account.setAccountNumber(accountNumber);
        account.setBalance(balance);
        account.setCurrencyCode(Constants.DEFAULT_CURRENCY_CODE);
        account.setStatus(AccountStatus.ACTIVE);
        return accountRepository.saveAndFlush(account);
    }

    protected ResponseEntity<TransferResponse> postTransfer(String source,
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
                TransferResponse.class);
    }

    protected ResponseEntity<String> postTransferRaw(String source,
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
                String.class);
    }
}
