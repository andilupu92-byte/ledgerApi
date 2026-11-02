package com.example.ledgerApi;

import com.example.ledgerApi.adapter.db.AccountRepository;
import com.example.ledgerApi.adapter.db.TransactionRepository;
import com.example.ledgerApi.adapter.web.dto.LedgerDtos.AccountResponse;
import com.example.ledgerApi.adapter.web.dto.LedgerDtos.EntryRequest;
import com.example.ledgerApi.adapter.web.dto.LedgerDtos.RecordTransactionRequest;
import com.example.ledgerApi.adapter.web.dto.LedgerDtos.TransactionResponse;
import com.example.ledgerApi.domain.Account;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    TransactionRepository transactionRepository;
    @Autowired
    AccountRepository accountRepository;

    @Test
    void recordsBalancedTransactionAndIsReadable() {
        AccountResponse to = openAccount("EUR", "0.00");

        RecordTransactionRequest request = new RecordTransactionRequest(
                "seed then transfer",
                List.of(
                        new EntryRequest("settlement-eur", "DEBIT", "50.00"),
                        new EntryRequest(to.id(), "CREDIT", "50.00")
                )
        );

        ResponseEntity<TransactionResponse> created = record(UUID.randomUUID().toString(), request);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().entries()).hasSize(2);

        TransactionResponse fetched = rest.get()
                .uri("/v1/transactions/{id}", created.getBody().id())
                .retrieve()
                .body(TransactionResponse.class);
        assertThat(fetched.id()).isEqualTo(created.getBody().id());
        assertThat(fetched.description()).isEqualTo("seed then transfer");
    }

    @Test
    void exampleTwoPayoutWithFeeThenReversal() {
        AccountResponse merchant = openAccount("EUR", "0.00");
        AccountResponse payouts = openAccount("EUR", "0.00");
        AccountResponse fees = openAccount("EUR", "0.00");

        record(UUID.randomUUID().toString(), new RecordTransactionRequest(
                "settle merchant",
                List.of(
                        new EntryRequest("settlement-eur", "DEBIT", "125.50"),
                        new EntryRequest(merchant.id(), "CREDIT", "125.50")
                )
        ));

        ResponseEntity<TransactionResponse> payout = record(UUID.randomUUID().toString(), new RecordTransactionRequest(
                "payout with fee",
                List.of(
                        new EntryRequest(merchant.id(), "DEBIT", "100.00"),
                        new EntryRequest(payouts.id(), "CREDIT", "98.50"),
                        new EntryRequest(fees.id(), "CREDIT", "1.50")
                )
        ));
        assertThat(payout.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(balance(merchant.id())).isEqualTo("25.50");
        assertThat(sumOfCurrency("EUR")).isZero();

        ResponseEntity<TransactionResponse> reversal = rest.post()
                .uri("/v1/transactions/{id}/reversal", payout.getBody().id())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .retrieve()
                .toEntity(TransactionResponse.class);
        assertThat(reversal.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reversal.getBody().reversesTransactionId()).isEqualTo(payout.getBody().id());
        assertThat(balance(merchant.id())).isEqualTo("125.50");
        assertThat(balance(payouts.id())).isEqualTo("0.00");
        assertThat(balance(fees.id())).isEqualTo("0.00");

        ResponseEntity<ProblemDetail> second = exchangePost(
                "/v1/transactions/" + payout.getBody().id() + "/reversal",
                UUID.randomUUID().toString(),
                null,
                ProblemDetail.class
        );
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(String.valueOf(second.getBody().getProperties().get("code"))).isEqualTo("ALREADY_REVERSED");
    }

    @Test
    void refusesUnbalancedUnknownCurrencyMismatchOverdraftAndPrecision() {
        AccountResponse merchant = openAccount("EUR", "0.00");
        AccountResponse payouts = openAccount("EUR", "0.00");
        AccountResponse yenMerchant = openAccount("JPY", "0");
        AccountResponse yenPayouts = openAccount("JPY", "0");

        record(UUID.randomUUID().toString(), new RecordTransactionRequest(
                "fund merchant",
                List.of(
                        new EntryRequest("settlement-eur", "DEBIT", "125.50"),
                        new EntryRequest(merchant.id(), "CREDIT", "125.50")
                )
        ));
        record(UUID.randomUUID().toString(), new RecordTransactionRequest(
                "payout with fee",
                List.of(
                        new EntryRequest(merchant.id(), "DEBIT", "100.00"),
                        new EntryRequest(payouts.id(), "CREDIT", "98.50"),
                        new EntryRequest(openAccount("EUR", "0.00").id(), "CREDIT", "1.50")
                )
        ));

        assertRefused(new RecordTransactionRequest("unbalanced", List.of(
                new EntryRequest(merchant.id(), "DEBIT", "100.00"),
                new EntryRequest(payouts.id(), "CREDIT", "99.00")
        )), "UNBALANCED_TRANSACTION");

        assertRefused(new RecordTransactionRequest("overdraft", List.of(
                new EntryRequest(merchant.id(), "DEBIT", "50.00"),
                new EntryRequest(payouts.id(), "CREDIT", "50.00")
        )), "OVERDRAFT_EXCEEDED");

        assertRefused(new RecordTransactionRequest("jpy fraction", List.of(
                new EntryRequest(yenMerchant.id(), "DEBIT", "100.5"),
                new EntryRequest(yenPayouts.id(), "CREDIT", "100.5")
        )), "INVALID_AMOUNT_PRECISION");

        assertRefused(new RecordTransactionRequest("fx", List.of(
                new EntryRequest(merchant.id(), "DEBIT", "10.00"),
                new EntryRequest(yenMerchant.id(), "CREDIT", "10")
        )), "CURRENCY_MISMATCH");

        ResponseEntity<ProblemDetail> oneEntry = exchangePost("/v1/transactions", UUID.randomUUID().toString(),
                Map.of("description", "one", "entries", List.of(
                        Map.of("accountId", merchant.id(), "direction", "CREDIT", "amount", "10.00")
                )), ProblemDetail.class);
        assertThat(oneEntry.getStatusCode().is4xxClientError()).isTrue();

        assertRefused(new RecordTransactionRequest("unknown", List.of(
                new EntryRequest("does-not-exist", "DEBIT", "10.00"),
                new EntryRequest(payouts.id(), "CREDIT", "10.00")
        )), "UNKNOWN_ACCOUNT");
    }

    @Test
    void refusedTransactionLeavesNothingBehind() {
        AccountResponse a = openAccount("EUR", "0.00");
        AccountResponse b = openAccount("EUR", "0.00");
        long txs = transactionRepository.countTransactions();
        long entries = transactionRepository.countEntries();

        assertRefused(new RecordTransactionRequest("unbalanced", List.of(
                new EntryRequest(a.id(), "DEBIT", "10.00"),
                new EntryRequest(b.id(), "CREDIT", "9.00")
        )), "UNBALANCED_TRANSACTION");

        assertThat(transactionRepository.countTransactions()).isEqualTo(txs);
        assertThat(transactionRepository.countEntries()).isEqualTo(entries);
        assertThat(balance(a.id())).isEqualTo("0.00");
        assertThat(balance(b.id())).isEqualTo("0.00");
    }

    @Test
    void openingAccountRejectsUnknownAndUnsupportedCurrencies() {
        ResponseEntity<ProblemDetail> euro = exchangePost("/v1/accounts", UUID.randomUUID().toString(),
                Map.of("currency", "EURO"), ProblemDetail.class);
        assertThat(euro.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(String.valueOf(euro.getBody().getProperties().get("code"))).isEqualTo("INVALID_CURRENCY");

        ResponseEntity<ProblemDetail> gbp = exchangePost("/v1/accounts", UUID.randomUUID().toString(),
                Map.of("currency", "GBP"), ProblemDetail.class);
        assertThat(gbp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(String.valueOf(gbp.getBody().getProperties().get("code"))).isEqualTo("UNSUPPORTED_CURRENCY");
    }

    @Test
    void idempotentRetriesReturnTheSameResource() {
        String key = "open-" + UUID.randomUUID();
        AccountResponse first = rest.post().uri("/v1/accounts")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("currency", "USD"))
                .retrieve()
                .body(AccountResponse.class);
        AccountResponse retry = rest.post().uri("/v1/accounts")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("currency", "USD"))
                .retrieve()
                .body(AccountResponse.class);
        assertThat(retry.id()).isEqualTo(first.id());

        ResponseEntity<ProblemDetail> conflict = exchangePost("/v1/accounts", key,
                Map.of("currency", "EUR"), ProblemDetail.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private void assertRefused(RecordTransactionRequest request, String code) {
        ResponseEntity<ProblemDetail> response = exchangePost(
                "/v1/transactions", UUID.randomUUID().toString(), request, ProblemDetail.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(String.valueOf(response.getBody().getProperties().get("code"))).isEqualTo(code);
    }

    private String balance(String accountId) {
        return rest.get().uri("/v1/accounts/{id}", accountId).retrieve().body(AccountResponse.class).balance();
    }

    private long sumOfCurrency(String currency) {
        return accountRepository.findAll().stream()
                .filter(a -> a.currency().name().equals(currency))
                .mapToLong(Account::balance)
                .sum();
    }
}
