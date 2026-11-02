package com.example.ledgerApi.application;

import com.example.ledgerApi.adapter.db.AccountRepository;
import com.example.ledgerApi.domain.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) { this.accounts = accounts; }

    @Transactional
    public Account open(String currencyCode, String overdraftLimit) {
        SupportedCurrency currency = SupportedCurrency.parse(currencyCode, "/currency");
        long limit = overdraftLimit == null ? 0
                : Money.toMinor(overdraftLimit, currency, "/overdraftLimit", false);
        Account account = new Account(UUID.randomUUID().toString(), currency, limit, false, 0, Instant.now());
        accounts.insert(account);
        return account;
    }

    @Transactional(readOnly = true)
    public Account get(String id) {
        return accounts.findById(id).orElseThrow(() -> new LedgerException(
                HttpStatus.NOT_FOUND, "UNKNOWN_ACCOUNT", "Unknown account", "Account " + id + " does not exist", null));
    }
}