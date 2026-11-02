package com.example.ledgerApi.adapter.db;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record OutboxEvent(
            Long id,
            String transactionId,
            String requestId,
            String payload
    ) {}

    public List<OutboxEvent> findUnpublishedForUpdate() {
        String sql = """
            SELECT id, transaction_id, request_id, payload
            FROM outbox
            WHERE published_at IS NULL
            ORDER BY id
            LIMIT 50
            FOR UPDATE SKIP LOCKED
            """;

        return jdbc.query(sql, (rs, i) -> new OutboxEvent(
                rs.getLong("id"),
                rs.getString("transaction_id"),
                rs.getString("request_id"),
                rs.getString("payload")
        ));
    }

    public void markAsPublished(Long id) {
        jdbc.update("UPDATE outbox SET published_at = ? WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC), id);
    }
}
