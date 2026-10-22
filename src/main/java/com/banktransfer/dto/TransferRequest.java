package com.banktransfer.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.banktransfer.util.MoneyUtil;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class TransferRequest {

    @NotBlank
    @Size(max = 64)
    private String sourceAccountNumber;

    @NotBlank
    @Size(max = 64)
    private String targetAccountNumber;

    @NotNull
    @DecimalMin(value = MoneyUtil.MIN_TRANSFER_AMOUNT)
    @DecimalMax(value = MoneyUtil.MAX_TRANSFER_AMOUNT, message = "이체 금액이 허용 자릿수를 초과했습니다.")
    @Digits(integer = MoneyUtil.MAX_INTEGER_DIGITS, fraction = MoneyUtil.KRW_SCALE,
            message = "이체 금액은 소수 없는 정수여야 합니다.")
    private BigDecimal amount;
}
