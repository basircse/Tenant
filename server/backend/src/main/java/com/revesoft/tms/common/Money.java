package com.revesoft.tms.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

public final class Money {

    private Money() {
    }

    public static BigDecimal of(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal sum(BigDecimal... values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            if (v != null) {
                total = total.add(v);
            }
        }
        return of(total);
    }

    public static String format(BigDecimal v, String currency) {
        return currency + " " + new DecimalFormat("#,##0.##").format(v == null ? BigDecimal.ZERO : v);
    }
}
