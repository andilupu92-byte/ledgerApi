package com.example.ledgerApi.adapter.web;

import com.example.ledgerApi.application.TransactionService;
import com.example.ledgerApi.config.Idempotent;
import com.example.ledgerApi.domain.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/transactions")
public class TransactionController {

    public record EntryRequest(
            @NotNull String accountId,
            @NotNull String direction, // "DEBIT" sau "CREDIT"
            @NotNull String amount
    ) {}

    public record RecordTransactionRequest(
            @NotNull String description,
            @NotEmpty List<EntryRequest> entries
    ) {}

    public record EntryResponse(
            Long id,
            String accountId,
            String direction,
            String amount,
            String balanceAfter
    ) {}

    public record TransactionResponse(
            String id,
            String description,
            String recordedAt,
            String reversesTransactionId,
            List<EntryResponse> entries
    ) {
        static TransactionResponse of(com.example.ledgerApi.domain.LedgerTransaction tx, List<com.example.ledgerApi.domain.Entry> entries) {
            List<EntryResponse> entryResponses = entries.stream()
                    .map(e -> new EntryResponse(
                            e.id(),
                            e.accountId(),
                            e.direction().name(),
                            Money.format(e.amount(), e.currency()),
                            Money.format(e.balanceAfter(), e.currency())
                    ))
                    .toList();

            return new TransactionResponse(
                    tx.id(),
                    tx.description(),
                    tx.recordedAt().toString(),
                    tx.reversesTransactionId(),
                    entryResponses
            );
        }
    }

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    @Idempotent(operation = "RECORD_TRANSACTION")
    ResponseEntity<TransactionResponse> record(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody @Valid RecordTransactionRequest request
    ) {
        var result = transactionService.recordTransaction(request, idempotencyKey);

        return ResponseEntity
                .created(URI.create("/v1/transactions/" + result.transaction().id()))
                .body(TransactionResponse.of(result.transaction(), result.entries()));
    }

    @PostMapping("/{id}/reversal")
    ResponseEntity<TransactionResponse> reverse(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable String id
    ) {
        var result = transactionService.reverseTransaction(id, idempotencyKey);
        return ResponseEntity
                .created(URI.create("/v1/transactions/" + result.transaction().id()))
                .body(TransactionResponse.of(result.transaction(), result.entries()));
    }
}