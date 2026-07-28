package com.banktransfer.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.banktransfer.model.Account;
import com.banktransfer.model.Customer;
import com.banktransfer.repository.CustomerRepository;

import jakarta.persistence.EntityManager;

@RestController
@RequestMapping("/api/dev")
@Profile("dev")
public class DevDataController {

    private static final Logger log = LoggerFactory.getLogger(DevDataController.class);

    private static final String[] BANKS = {
            Customer.BANK_KB,
            Customer.BANK_NH,
            Customer.BANK_WOORI,
            Customer.BANK_HANA,
            Customer.BANK_KAKAO,
            Customer.BANK_TOSS
    };

    private static final String[] GENDERS = {
            Customer.GENDER_MALE,
            Customer.GENDER_FEMALE
    };

    private final CustomerRepository customerRepository;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    public DevDataController(
            CustomerRepository customerRepository,
            EntityManager entityManager,
            TransactionTemplate transactionTemplate) {
        this.customerRepository = customerRepository;
        this.entityManager = entityManager;
        this.transactionTemplate = transactionTemplate;
    }

    @PostMapping("/generate-accounts")
    public ResponseEntity<Map<String, Object>> generateDummyCustomerWithAccount() {
        int totalCount = 2_000_000;     // 총 200만 건 생성
        int batchSize = 10_000;         // 한 번에 1만 건씩 배치 저장
        int createdCount = 0;

        AtomicReference<SampleCustomer> sampleRef = new AtomicReference<>();

        for (int offset = 0; offset < totalCount; offset += batchSize) {
            int currentBatchSize = Math.min(batchSize, totalCount - offset);
            List<Customer> batch = new ArrayList<>(currentBatchSize);

            for (int i = 0; i < currentBatchSize; i++) {
                Customer customer = new Customer();
                customer.setName(randomName(20, 60));
                customer.setAge(randomAge());
                customer.setBank(randomBank());
                customer.setGender(randomGender());

                Account account = new Account();
                account.setAccountNumber(generateAccountNumber());
                customer.addAccount(account);

                batch.add(customer);
            }

            final int batchOffset = offset;
            final int batchCurrentSize = currentBatchSize;
            transactionTemplate.executeWithoutResult(status -> {
                List<Customer> savedBatch = customerRepository.saveAll(batch);
                entityManager.flush();

                if (sampleRef.get() == null && !savedBatch.isEmpty()) {
                    Customer first = savedBatch.get(0);
                    String accountNumber = first.getAccounts().isEmpty()
                            ? null
                            : first.getAccounts().get(0).getAccountNumber();
                    sampleRef.set(new SampleCustomer(
                            first.getId(),
                            first.getName(),
                            first.getAge(),
                            first.getBank(),
                            first.getGender(),
                            accountNumber));
                }

                entityManager.clear();
            });

            createdCount += currentBatchSize;
            log.info("DevDataController: batch committed, offset={}, batchSize={}, createdSoFar={}",
                    batchOffset, batchCurrentSize, createdCount);
        }

        SampleCustomer sample = sampleRef.get();
        log.info("DevDataController: created {} dummy customers in total (sampleId={}, sampleAccount={})",
                createdCount,
                sample != null ? sample.id() : null,
                sample != null ? sample.accountNumber() : null);

        Map<String, Object> body = new HashMap<>();
        body.put("message", "더미 고객 및 계좌가 생성되었습니다.");
        body.put("createdCustomerCount", createdCount);
        body.put("sampleCustomerId", sample != null ? sample.id() : null);
        body.put("sampleName", sample != null ? sample.name() : null);
        body.put("sampleAge", sample != null ? sample.age() : null);
        body.put("sampleBank", sample != null ? sample.bank() : null);
        body.put("sampleGender", sample != null ? sample.gender() : null);
        body.put("sampleAccountNumber", sample != null ? sample.accountNumber() : null);

        return ResponseEntity.ok(body);
    }

    private record SampleCustomer(
            Long id,
            String name,
            Integer age,
            String bank,
            String gender,
            String accountNumber) {
    }

    private String randomName(int minLength, int maxLength) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int length = random.nextInt(minLength, maxLength + 1);
        StringBuilder sb = new StringBuilder(length);
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < length; i++) {
            int idx = random.nextInt(chars.length());
            sb.append(chars.charAt(idx));
        }
        return sb.toString();
    }

    private int randomAge() {
        return ThreadLocalRandom.current().nextInt(1, 100); // 1 ~ 99
    }

    private String randomBank() {
        int idx = ThreadLocalRandom.current().nextInt(BANKS.length);
        return BANKS[idx];
    }

    private String randomGender() {
        int idx = ThreadLocalRandom.current().nextInt(GENDERS.length);
        return GENDERS[idx];
    }

    private String generateAccountNumber() {
        // UUID 기반으로 계좌번호를 생성하여 유니크 제약(accounts.uq_account_number)을 안정적으로 만족시킨다.
        return "D-" + UUID.randomUUID();
    }
}


