package com.example.ledgerApi.adapter.db;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {
    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SavedResponse(int status, String body) {}

    public Optional<SavedResponse> findByKey(String idemKey) {
        String sql = "SELECT response_status, response_body FROM idempotency_key WHERE idem_key = ?";
        return jdbc.query(sql, (rs, i) -> new SavedResponse(
                rs.getInt("response_status"),
                rs.getString("response_body")
        ), idemKey).stream().findFirst();
    }

    public void save(String idemKey, String operation, String requestHash, int status, String body) {
        jdbc.update("""
            INSERT INTO idempotency_key (idem_key, operation, request_hash, response_status, response_body, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
                idemKey, operation, requestHash, status, body,
                LocalDateTime.now(ZoneOffset.UTC)
        );
    }
}