package com.example.ledgerApi.adapter.web.dto;

import com.example.ledgerApi.domain.Entry;
import com.example.ledgerApi.domain.LedgerTransaction;
import com.example.ledgerApi.domain.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class LedgerDtos {
    private LedgerDtos() {}

    public record OpenAccountRequest(
            @NotBlank String currency,
            String overdraftLimit
    ) {}

    public record AccountResponse(String id, String currency, String overdraftLimit, String balance) {}

    public record EntryRequest(
            @NotBlank String accountId,
            @NotBlank String direction,
            @NotBlank String amount
    ) {}

    public record RecordTransactionRequest(
            @NotBlank String description,
            @NotEmpty @Size(min = 2) @Valid List<EntryRequest> entries
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
        public static TransactionResponse of(LedgerTransaction tx, List<Entry> entries) {
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

    public record PaymentSettledSample(
            @NotBlank String eventId,
            @NotBlank String paymentId,
            @NotBlank String merchantAccountId,
            @NotBlank String amount,
            @NotBlank String currency
    ) {}
}
