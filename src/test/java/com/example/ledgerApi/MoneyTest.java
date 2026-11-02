package com.example.ledgerApi;

import com.example.ledgerApi.domain.LedgerException;
import com.example.ledgerApi.domain.Money;
import com.example.ledgerApi.domain.SupportedCurrency;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void parsesAndFormatsWithoutFloatingPoint() {
        assertThat(Money.toMinor("0.10", SupportedCurrency.EUR, "amount", true)
                + Money.toMinor("0.20", SupportedCurrency.EUR, "amount", true))
                .isEqualTo(Money.toMinor("0.30", SupportedCurrency.EUR, "amount", true));
        assertThat(Money.format(12550, SupportedCurrency.EUR)).isEqualTo("125.50");
        assertThat(Money.format(15000, SupportedCurrency.JPY)).isEqualTo("15000");
    }

    @Test
    void rejectsTooManyDecimalPlaces() {
        assertThatThrownBy(() -> Money.toMinor("100.5", SupportedCurrency.JPY, "amount", true))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("decimal places");
    }
}
