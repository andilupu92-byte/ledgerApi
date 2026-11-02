package com.example.ledgerApi.domain;

import java.math.BigDecimal;
import org.springframework.http.HttpStatus;

public final class Money {
    private Money() {}

    /** Parses a decimal string into minor units (125.50 EUR -> 12550). Never touches double. */
    public static long toMinor(String text, SupportedCurrency currency, String pointer, boolean mustBePositive) {
        if (text == null || !text.matches("-?\\d+(\\.\\d+)?")) {
            throw bad("INVALID_AMOUNT", "Amount must be a plain decimal string, e.g. \"125.50\"", pointer);
        }
        BigDecimal value = new BigDecimal(text).stripTrailingZeros();
        if (value.scale() > currency.fractionDigits()) {
            throw bad("INVALID_AMOUNT_PRECISION",
                    currency + " allows at most " + currency.fractionDigits() + " decimal places", pointer);
        }
        if (mustBePositive ? value.signum() <= 0 : value.signum() < 0) {
            throw bad("INVALID_AMOUNT", mustBePositive ? "Amount must be positive" : "Amount must not be negative", pointer);
        }
        try {
            return value.movePointRight(currency.fractionDigits()).longValueExact();
        } catch (ArithmeticException e) {
            throw bad("INVALID_AMOUNT", "Amount is too large", pointer);
        }
    }

    public static String format(long minor, SupportedCurrency currency) {
        return BigDecimal.valueOf(minor, currency.fractionDigits()).toPlainString();
    }

    private static LedgerException bad(String code, String detail, String pointer) {
        return new LedgerException(HttpStatus.UNPROCESSABLE_ENTITY, code, "Invalid amount", detail, pointer);
    }
}