package com.example.ledgerApi.adapter.msg;

import com.example.ledgerApi.domain.SupportedCurrency;
import java.math.BigDecimal;

public record PaymentSettledMessage(
        String paymentId,
        String merchantAccountId,
        String settlementAccountId,
        BigDecimal amount,
        SupportedCurrency currency
) {}