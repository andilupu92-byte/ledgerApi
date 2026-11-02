package com.example.ledgerApi.domain;

import java.time.Instant;

public record Account(String id, SupportedCurrency currency, long overdraftLimit,
                      boolean unlimitedOverdraft, long balance, Instant createdAt) {}