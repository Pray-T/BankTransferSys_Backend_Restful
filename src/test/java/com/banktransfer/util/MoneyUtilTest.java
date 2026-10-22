package com.banktransfer.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.banktransfer.exception.AppErrorCode;
import com.banktransfer.exception.BusinessException;

class MoneyUtilTest {

    @Test
    @DisplayName("정상 정수와 끝자리 0뿐인 소수는 scale 0 정수로 맞춘다")
    void wholeAmounts_normalizeToScaleZero() {
        assertScaleZero("1000", "1000");
        assertScaleZero("1000.00", "1000");
        assertScaleZero("1", "1");
        assertScaleZero(MoneyUtil.MAX_TRANSFER_AMOUNT, MoneyUtil.MAX_TRANSFER_AMOUNT);
    }

    @Test
    @DisplayName("소수 금액은 반올림하지 않고 거절한다")
    void fractionalAmount_isRejectedWithoutRounding() {
        BigDecimal input = new BigDecimal("1000.6");
        assertThatThrownBy(() -> MoneyUtil.scaleKRW(input))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AppErrorCode.VALIDATION_ERROR);
        assertThat(input).isEqualByComparingTo("1000.6");
        assertThatThrownBy(() -> MoneyUtil.scaleKRW(new BigDecimal("1.25E+1")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("음수와 0원은 이체 금액으로 받지 않는다")
    void nonPositiveAmounts_areRejected() {
        assertThatThrownBy(() -> MoneyUtil.scaleKRW(new BigDecimal("-1")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AppErrorCode.VALIDATION_ERROR);
        assertThatThrownBy(() -> MoneyUtil.scaleKRW(BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AppErrorCode.VALIDATION_ERROR);
        assertThatThrownBy(() -> MoneyUtil.scaleKRW(new BigDecimal("0.00")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("정수로 떨어지는 지수 표기는 평문 정수로, 자릿수 초과는 거절한다")
    void scientificNotation_acceptsExactIntegersAndRejectsOverflow() {
        BigDecimal exact = MoneyUtil.scaleKRW(new BigDecimal("1E+3"));
        assertThat(exact.scale()).isZero();
        assertThat(exact.toPlainString()).isEqualTo("1000");

        assertThatThrownBy(() -> MoneyUtil.scaleKRW(new BigDecimal("1e20")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> MoneyUtil.scaleKRW(new BigDecimal("1e10000000")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(AppErrorCode.VALIDATION_ERROR);
        assertThatThrownBy(() -> MoneyUtil.scaleKRW(new BigDecimal("1000000000000000")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("응답과 해시는 scale 0 평문을 쓰고 남은 소수는 반올림하지 않는다")
    void responseAndHashPlain_useScaleZero() {
        assertThat(MoneyUtil.responseAmount(new BigDecimal("99000.00")).toPlainString()).isEqualTo("99000");
        assertThat(MoneyUtil.responseAmount(BigDecimal.ZERO).scale()).isZero();
        assertThat(MoneyUtil.plain(new BigDecimal("1000.00"))).isEqualTo("1000");
        assertThatThrownBy(() -> MoneyUtil.responseAmount(new BigDecimal("1000.6")))
                .isInstanceOf(IllegalStateException.class);
    }

    private static void assertScaleZero(String input, String plain) {
        BigDecimal scaled = MoneyUtil.scaleKRW(new BigDecimal(input));
        assertThat(scaled.scale()).isZero();
        assertThat(scaled.toPlainString()).isEqualTo(plain);
    }
}
