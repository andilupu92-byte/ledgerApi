package com.example.ledgerApi.adapter.web;

import com.example.ledgerApi.adapter.web.dto.LedgerDtos.AccountResponse;
import com.example.ledgerApi.adapter.web.dto.LedgerDtos.OpenAccountRequest;
import com.example.ledgerApi.application.AccountService;
import com.example.ledgerApi.domain.Account;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/accounts")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<AccountResponse> open(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody @Valid OpenAccountRequest request
    ) {
        Account account = service.open(request, idempotencyKey);
        return ResponseEntity.created(URI.create("/v1/accounts/" + account.id()))
                .body(AccountService.toResponse(account));
    }

    @GetMapping("/{id}")
    AccountResponse get(@PathVariable String id) {
        return AccountService.toResponse(service.get(id));
    }
}
