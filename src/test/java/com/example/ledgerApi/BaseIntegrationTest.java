package com.example.ledgerApi;

import com.example.ledgerApi.adapter.web.dto.LedgerDtos.AccountResponse;
import com.example.ledgerApi.adapter.web.dto.LedgerDtos.OpenAccountRequest;
import com.example.ledgerApi.adapter.web.dto.LedgerDtos.RecordTransactionRequest;
import com.example.ledgerApi.adapter.web.dto.LedgerDtos.TransactionResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
public abstract class BaseIntegrationTest {

    @LocalServerPort
    protected int port;

    protected RestClient rest;

    @BeforeEach
    void initClient() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    protected AccountResponse openAccount(String currency, String overdraftLimit) {
        return rest.post()
                .uri("/v1/accounts")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OpenAccountRequest(currency, overdraftLimit))
                .retrieve()
                .body(AccountResponse.class);
    }

    protected ResponseEntity<TransactionResponse> record(String key, RecordTransactionRequest request) {
        return rest.post()
                .uri("/v1/transactions")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toEntity(TransactionResponse.class);
    }

    protected RestClient.RequestHeadersSpec<?> get(String path) {
        return rest.get().uri(path);
    }

    protected <T> ResponseEntity<T> exchangePost(String path, String key, Object body, Class<T> type) {
        RestClient.RequestBodySpec spec = rest.post()
                .uri(path)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            spec.body(body);
        }

        return spec.exchange((req, res) -> {
            HttpStatusCode status = res.getStatusCode();

            // Normalize Spring 7's 422 representation
            if (status.value() == 422) {
                status = HttpStatus.UNPROCESSABLE_ENTITY;
            }

            return ResponseEntity.status(status)
                    .headers(res.getHeaders())
                    .body(res.bodyTo(type));
        });
    }

    protected int postStatus(String path, String key, Object body) {
        RestClient.RequestBodySpec spec = rest.post()
                .uri(path)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            spec.body(body);
        }
        return spec.exchange((req, res) -> {
            res.getBody().readAllBytes();
            return res.getStatusCode().value();
        });
    }
}
