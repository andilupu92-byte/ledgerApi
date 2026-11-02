package com.example.ledgerApi.domain;


public record Entry(
        Long id,
        String transactionId,
        String accountId,
        SupportedCurrency currency,
        EntryDirection direction,
        long amount,
        long balanceAfter
) {}