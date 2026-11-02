package com.example.ledgerApi.adapter.web;

import com.example.ledgerApi.application.AccountService;
import com.example.ledgerApi.config.Idempotent;
import com.example.ledgerApi.domain.Account;
import com.example.ledgerApi.domain.Money;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/accounts")
public class AccountController {
    public record OpenAccountRequest(String currency, String overdraftLimit) {}

    public record AccountResponse(String id, String currency, String overdraftLimit, String balance) {
        static AccountResponse of(Account a) {
            return new AccountResponse(a.id(), a.currency().name(),
                    a.unlimitedOverdraft() ? null : Money.format(a.overdraftLimit(), a.currency()),
                    Money.format(a.balance(), a.currency()));
        }
    }

    private final AccountService service;

    public AccountController(AccountService service) { this.service = service; }

    @PostMapping
    @Idempotent(operation = "OPEN_ACCOUNT")
    ResponseEntity<AccountResponse> open(@RequestBody OpenAccountRequest request) {
        Account a = service.open(request.currency(), request.overdraftLimit());
        return ResponseEntity.created(URI.create("/v1/accounts/" + a.id())).body(AccountResponse.of(a));
    }

    @GetMapping("/{id}")
    AccountResponse get(@PathVariable String id) {
        return AccountResponse.of(service.get(id));
    }
}