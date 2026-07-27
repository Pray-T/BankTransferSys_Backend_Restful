package com.banktransfer.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.banktransfer.model.Account;
import com.banktransfer.model.Customer;
import com.banktransfer.repository.CustomerRepository;

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

    public DevDataController(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    /**
     * 요청이 올 때마다 랜덤 속성을 가진 더미 Customer 100명과
     * 각 Customer 에 연관된 계좌 1개씩(총 100개)을 생성한다.
     *
     * - 이름: 20 ~ 60자의 랜덤 문자열
     * - 나이: 1 ~ 99
     * - 은행: KB, NH, WOORI, HANA, KAKAO, TOSS 중 랜덤 1개
     * - 성별: MALE, FEMALE 중 랜덤 1개
     */
    @PostMapping("/generate-accounts")
    @Transactional
    public ResponseEntity<Map<String, Object>> generateDummyCustomerWithAccount() {
        int totalCount = 2_000_000;     // 총 200만 건 생성
        int batchSize = 10_000;         // 한 번에 1만 건씩 배치 저장
        int createdCount = 0;

        Customer sample = null;
        String sampleAccountNumber = null;

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

            List<Customer> savedBatch = customerRepository.saveAll(batch);
            createdCount += currentBatchSize;

            if (sample == null && !savedBatch.isEmpty()) {
                sample = savedBatch.get(0);
                sampleAccountNumber = sample.getAccounts().isEmpty()
                        ? null
                        : sample.getAccounts().get(0).getAccountNumber();
            }

            log.info("DevDataController: batch saved, offset={}, batchSize={}, createdSoFar={}",
                    offset, currentBatchSize, createdCount);
        }

        log.info("DevDataController: created {} dummy customers in total (sampleId={}, sampleAccount={})",
                createdCount, sample != null ? sample.getId() : null, sampleAccountNumber);

        Map<String, Object> body = new HashMap<>();
        body.put("message", "더미 고객 및 계좌가 생성되었습니다.");
        body.put("createdCustomerCount", createdCount);
        body.put("sampleCustomerId", sample != null ? sample.getId() : null);
        body.put("sampleName", sample != null ? sample.getName() : null);
        body.put("sampleAge", sample != null ? sample.getAge() : null);
        body.put("sampleBank", sample != null ? sample.getBank() : null);
        body.put("sampleGender", sample != null ? sample.getGender() : null);
        body.put("sampleAccountNumber", sampleAccountNumber);

        return ResponseEntity.ok(body);
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


