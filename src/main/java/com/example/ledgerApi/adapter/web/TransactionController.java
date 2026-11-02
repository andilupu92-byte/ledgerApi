package com.example.ledgerApi.adapter.web;

import com.example.ledgerApi.adapter.web.dto.LedgerDtos.RecordTransactionRequest;
import com.example.ledgerApi.adapter.web.dto.LedgerDtos.TransactionResponse;
import com.example.ledgerApi.application.TransactionService;
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
@RequestMapping("/v1/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    ResponseEntity<TransactionResponse> record(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody @Valid RecordTransactionRequest request
    ) {
        var result = transactionService.recordTransaction(request, idempotencyKey);
        return ResponseEntity
                .created(URI.create("/v1/transactions/" + result.transaction().id()))
                .body(result.toResponse());
    }

    @GetMapping("/{id}")
    TransactionResponse get(@PathVariable String id) {
        return transactionService.get(id).toResponse();
    }

    @PostMapping("/{id}/reversal")
    ResponseEntity<TransactionResponse> reverse(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable String id
    ) {
        var result = transactionService.reverseTransaction(id, idempotencyKey);
        return ResponseEntity
                .created(URI.create("/v1/transactions/" + result.transaction().id()))
                .body(result.toResponse());
    }
}
