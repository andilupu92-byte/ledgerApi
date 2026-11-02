
package com.example.ledgerApi.domain;

import org.springframework.http.HttpStatus;

import java.util.Currency;

public enum SupportedCurrency {

    EUR(2),
    USD(2),
    JPY(0);

    private static final String CURRENCY_CODE_PATTERN = "[A-Z]{3}";

    private final int fractionDigits;

    SupportedCurrency(int fractionDigits) {
        this.fractionDigits = fractionDigits;
    }

    public int fractionDigits() {
        return fractionDigits;
    }

    /**
     * EURO / ABC -> INVALID_CURRENCY
     * GBP -> UNSUPPORTED_CURRENCY
     */
    public static SupportedCurrency parse(String code, String pointer) {
        if (code == null || !code.matches(CURRENCY_CODE_PATTERN)) {
            throw invalid(pointer);
        }

        try {
            Currency.getInstance(code);
        } catch (IllegalArgumentException exception) {
            throw invalid(pointer);
        }

        try {
            return SupportedCurrency.valueOf(code);
        } catch (IllegalArgumentException exception) {
            throw new LedgerException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "UNSUPPORTED_CURRENCY",
                    "Currency not supported",
                    "Valid ISO 4217 code, but the ledger supports only EUR, USD and JPY",
                    pointer
            );
        }
    }

    private static LedgerException invalid(String pointer) {
        return new LedgerException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_CURRENCY",
                "Invalid currency",
                "Not an ISO 4217 currency code",
                pointer
        );
    }
}
