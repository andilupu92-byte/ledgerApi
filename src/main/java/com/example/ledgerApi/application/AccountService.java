package com.example.ledgerApi.application;

import com.example.ledgerApi.adapter.db.AccountRepository;
import com.example.ledgerApi.adapter.web.dto.LedgerDtos.AccountResponse;
import com.example.ledgerApi.adapter.web.dto.LedgerDtos.OpenAccountRequest;
import com.example.ledgerApi.domain.Account;
import com.example.ledgerApi.domain.LedgerException;
import com.example.ledgerApi.domain.Money;
import com.example.ledgerApi.domain.SupportedCurrency;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class AccountService {
    private final AccountRepository accounts;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public AccountService(AccountRepository accounts, IdempotencyService idempotencyService, ObjectMapper objectMapper) {
        this.accounts = accounts;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Account open(OpenAccountRequest request, String idempotencyKey) {
        String hash = idempotencyService.hash(request);
        Optional<IdempotencyService.Replay> replay = idempotencyService.begin(idempotencyKey, "OPEN_ACCOUNT", hash);
        if (replay.isPresent()) {
            AccountResponse stored = objectMapper.readValue(replay.get().body(), AccountResponse.class);
            return get(stored.id());
        }

        SupportedCurrency currency = SupportedCurrency.parse(request.currency(), "currency");
        long limit = request.overdraftLimit() == null
                ? 0
                : Money.toMinor(request.overdraftLimit(), currency, "overdraftLimit", false);
        Account account = new Account(UUID.randomUUID().toString(), currency, limit, false, 0, Instant.now());
        accounts.insert(account);
        idempotencyService.complete(idempotencyKey, 201, toResponse(account));
        return account;
    }

    @Transactional(readOnly = true)
    public Account get(String id) {
        return accounts.findById(id).orElseThrow(() -> new LedgerException(
                HttpStatus.NOT_FOUND, "UNKNOWN_ACCOUNT", "Unknown account",
                "Account " + id + " does not exist", null));
    }

    public static AccountResponse toResponse(Account a) {
        return new AccountResponse(
                a.id(),
                a.currency().name(),
                a.unlimitedOverdraft() ? null : Money.format(a.overdraftLimit(), a.currency()),
                Money.format(a.balance(), a.currency())
        );
    }
}
