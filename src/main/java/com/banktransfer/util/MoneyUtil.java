package com.banktransfer.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.banktransfer.exception.AppErrorCode;
import com.banktransfer.exception.BusinessException;

/**
 * 원화는 소수 단위가 없다. 금액은 scale 0 정수만 허용하고, 소수는 반올림하지 않고 거절한다.
 */
public final class MoneyUtil {

    public static final int KRW_SCALE = 0;

    public static final int MAX_INTEGER_DIGITS = 15;

    public static final String MIN_TRANSFER_AMOUNT = "1";

    /** {@code MAX_INTEGER_DIGITS}자리 최댓값. 컬럼 DECIMAL(19,0)보다 입력 한도를 좁혀 둔다. */
    public static final String MAX_TRANSFER_AMOUNT = "999999999999999";

    private static final BigDecimal MAX_TRANSFER = new BigDecimal(MAX_TRANSFER_AMOUNT);

    private MoneyUtil() {
    }

    /**
     * 이체 입력 금액을 원화 정수로 확정한다.
     * 순서: 자릿수 검사, 소수 거부, {@code setScale(0, UNNECESSARY)}, 1원 이상 상한 이하.
     * 자릿수 초과(예: {@code 1e10000000})는 값을 펼치기 전에 거절한다.
     */
    public static BigDecimal scaleKRW(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        long integerDigits = (long) stripped.precision() - stripped.scale();
        if (integerDigits > MAX_INTEGER_DIGITS) {
            throw validation("이체 금액은 소수 없는 " + MAX_INTEGER_DIGITS + "자리 이하의 정수여야 합니다.");
        }
        if (stripped.scale() > 0) {
            throw validation("원화 금액은 소수점을 허용하지 않습니다.");
        }
        BigDecimal scaled;
        try {
            scaled = value.setScale(KRW_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw validation("원화 금액은 소수점을 허용하지 않습니다.");
        }
        if (scaled.signum() <= 0 || scaled.compareTo(MAX_TRANSFER) > 0) {
            throw validation("이체 금액은 " + MIN_TRANSFER_AMOUNT + "원 이상 " + MAX_TRANSFER_AMOUNT + "원 이하여야 합니다.");
        }
        return scaled;
    }

    /**
     * 응답·저장 직전의 금액을 scale 0으로 맞춘다. 0원 잔액도 허용한다.
     * 소수 자리가 남아 있으면 반올림하지 않는다.
     */
    public static BigDecimal responseAmount(BigDecimal value) {
        if (value == null) {
            return null;
        }
        try {
            return value.setScale(KRW_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalStateException("저장된 원화 금액에 소수 자리가 있습니다.", ex);
        }
    }

    /** 멱등성 해시에 넣는 금액. scale 0의 평문이라 {@code 1000}과 {@code 1000.00}이 갈라지지 않는다. */
    public static String plain(BigDecimal value) {
        BigDecimal scaled = responseAmount(value);
        if (scaled == null) {
            throw new IllegalArgumentException("금액이 없습니다.");
        }
        return scaled.toPlainString();
    }

    private static BusinessException validation(String message) {
        return new BusinessException(AppErrorCode.VALIDATION_ERROR, message);
    }
}
