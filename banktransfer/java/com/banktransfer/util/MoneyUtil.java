package com.banktransfer.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class MoneyUtil {
    private MoneyUtil() {
    }

    public static BigDecimal scale2(BigDecimal value) {
        if (value == null) {
            return null;
        }
        
        return value.setScale(0, RoundingMode.HALF_UP);
    }
}

