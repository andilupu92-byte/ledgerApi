package com.example.ledgerApi.application;

import com.example.ledgerApi.adapter.db.IdempotencyRepository;
import com.example.ledgerApi.domain.LedgerException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class IdempotencyService {

    public record Replay(int status, String body) {}

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public String hash(Object request) {
        try {
            return sha256(objectMapper.writeValueAsString(request));
        } catch (Exception e) {
            throw new IllegalStateException("Could not hash request", e);
        }
    }

    /**
     * Claims the idempotency key in the current transaction. Concurrent retries block on
     * the unique primary key until the first attempt commits, then replay the stored body.
     * Returns empty when this caller owns the attempt and should execute the operation.
     */
    public Optional<Replay> begin(String key, String operation, String requestHash) {
        try {
            repository.insertPlaceholder(key, operation, requestHash);
            return Optional.empty();
        } catch (DuplicateKeyException ex) {
            IdempotencyRepository.SavedResponse saved = repository.findByKeyForUpdate(key)
                    .orElseThrow(() -> ex);
            if (!requestHash.equals(saved.requestHash())) {
                throw new LedgerException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency key reused",
                        "The Idempotency-Key was already used with a different request",
                        null);
            }
            return Optional.of(new Replay(saved.status(), saved.body()));
        }
    }

    public void complete(String key, int status, Object body) {
        repository.complete(key, status, writeJson(body));
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise idempotent response", e);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
