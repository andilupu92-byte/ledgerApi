package com.example.ledgerApi.domain;

import java.time.Instant;

public record LedgerTransaction(
        String id,
        String description,
        Instant recordedAt,
        String reversesTransactionId
) {}
