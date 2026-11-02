package com.example.ledgerApi.adapter.msg;

public record PaymentSettledMessage(
        String eventId,
        String paymentId,
        String merchantAccountId,
        String amount,
        String currency
) {}
