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

    public record SavedResponse(int status, String body, String requestHash) {}

    public Optional<SavedResponse> findByKey(String idemKey) {
        String sql = "SELECT response_status, response_body, request_hash FROM idempotency_key WHERE idem_key = ?";
        return jdbc.query(sql, (rs, i) -> new SavedResponse(
                rs.getInt("response_status"),
                rs.getString("response_body"),
                rs.getString("request_hash")
        ), idemKey).stream().findFirst();
    }

    public Optional<SavedResponse> findByKeyForUpdate(String idemKey) {
        String sql = """
                SELECT response_status, response_body, request_hash
                FROM idempotency_key
                WHERE idem_key = ?
                FOR UPDATE
                """;
        return jdbc.query(sql, (rs, i) -> new SavedResponse(
                rs.getInt("response_status"),
                rs.getString("response_body"),
                rs.getString("request_hash")
        ), idemKey).stream().findFirst();
    }

    public void insertPlaceholder(String idemKey, String operation, String requestHash) {
        jdbc.update("""
            INSERT INTO idempotency_key (idem_key, operation, request_hash, response_status, response_body, created_at)
            VALUES (?, ?, ?, 0, CAST('{}' AS JSON), ?)
            """,
                idemKey, operation, requestHash,
                LocalDateTime.now(ZoneOffset.UTC)
        );
    }

    public void complete(String idemKey, int status, String body) {
        jdbc.update("""
            UPDATE idempotency_key
            SET response_status = ?, response_body = CAST(? AS JSON)
            WHERE idem_key = ?
            """,
                status, body, idemKey
        );
    }

    public void save(String idemKey, String operation, String requestHash, int status, String body) {
        jdbc.update("""
            INSERT INTO idempotency_key (idem_key, operation, request_hash, response_status, response_body, created_at)
            VALUES (?, ?, ?, ?, CAST(? AS JSON), ?)
            """,
                idemKey, operation, requestHash, status, body,
                LocalDateTime.now(ZoneOffset.UTC)
        );
    }
}
