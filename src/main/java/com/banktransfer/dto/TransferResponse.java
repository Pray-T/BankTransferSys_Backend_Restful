package com.banktransfer.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.banktransfer.model.TransferStatus;
import com.banktransfer.util.MoneyUtil;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class TransferResponse {

    private Long transferId; // 이체 고유 식별자
    private TransferStatus status;  // 이체 상태
    private LocalDateTime executedAt; // 이체 실행 시간
    private String sourceAccountNumber; // 출금 계좌 번호
    private String targetAccountNumber; // 입금 계좌 번호
    private BigDecimal amount; // 이체 금액
    private String currencyCode; // 통화 코드
    private BigDecimal sourceBalanceAfter; // 출금 후 잔액
    private BigDecimal targetBalanceAfter; // 입금 후 잔액

    /**
     * 생성, 재응답, 조회가 같은 정수 표기를 쓰도록 금액·잔액을 scale 0으로 맞춘다.
     */
    public static TransferResponse of(Long transferId,
                                      TransferStatus status,
                                      LocalDateTime executedAt,
                                      String sourceAccountNumber,
                                      String targetAccountNumber,
                                      BigDecimal amount,
                                      String currencyCode,
                                      BigDecimal sourceBalanceAfter,
                                      BigDecimal targetBalanceAfter) {
        TransferResponse response = new TransferResponse();
        response.setTransferId(transferId);
        response.setStatus(status);
        response.setExecutedAt(executedAt);
        response.setSourceAccountNumber(sourceAccountNumber);
        response.setTargetAccountNumber(targetAccountNumber);
        response.setAmount(MoneyUtil.responseAmount(amount));
        response.setCurrencyCode(currencyCode);
        response.setSourceBalanceAfter(MoneyUtil.responseAmount(sourceBalanceAfter));
        response.setTargetBalanceAfter(MoneyUtil.responseAmount(targetBalanceAfter));
        return response;
    }
}
