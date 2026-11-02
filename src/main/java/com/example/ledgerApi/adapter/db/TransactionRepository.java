package com.example.ledgerApi.adapter.db;

import com.example.ledgerApi.domain.*;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionRepository {
    private final JdbcTemplate jdbc;

    public TransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertTransaction(LedgerTransaction tx) {
        jdbc.update("""
            INSERT INTO ledger_transaction (id, description, recorded_at, reverses_transaction_id)
            VALUES (?, ?, ?, ?)""",
                tx.id(), tx.description(),
                LocalDateTime.ofInstant(tx.recordedAt(), ZoneOffset.UTC),
                tx.reversesTransactionId()
        );
    }

    public long insertEntry(Entry entry) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO entry (transaction_id, account_id, direction, amount, balance_after)
                VALUES (?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, entry.transactionId());
            ps.setString(2, entry.accountId());
            ps.setString(3, entry.direction().name());
            ps.setLong(4, entry.amount());
            ps.setLong(5, entry.balanceAfter());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void insertOutbox(String transactionId, String requestId, String payloadJson) {
        jdbc.update("""
            INSERT INTO outbox (transaction_id, request_id, payload, created_at)
            VALUES (?, ?, ?, ?)""",
                transactionId, requestId, payloadJson,
                LocalDateTime.now(ZoneOffset.UTC)
        );
    }

    public Optional<LedgerTransaction> findTransactionById(String id) {
        return jdbc.query("SELECT * FROM ledger_transaction WHERE id = ?", (rs, i) -> new LedgerTransaction(
                rs.getString("id"),
                rs.getString("description"),
                rs.getObject("recorded_at", java.time.LocalDateTime.class).atZone(java.time.ZoneOffset.UTC).toInstant(),
                rs.getString("reverses_transaction_id")
        ), id).stream().findFirst();
    }

    public boolean existsByReversesTransactionId(String originalId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transaction WHERE reverses_transaction_id = ?", Integer.class, originalId);
        return count != null && count > 0;
    }

    public List<Entry> findEntriesByTransactionId(String transactionId) {
        return jdbc.query("SELECT * FROM entry WHERE transaction_id = ?", (rs, i) -> new Entry(
                rs.getLong("id"),
                rs.getString("transaction_id"),
                rs.getString("account_id"),
                SupportedCurrency.valueOf(rs.getString("currency") != null ? rs.getString("currency") : "EUR"), // Ajustează dacă ții moneda în cont sau în entry
                EntryDirection.valueOf(rs.getString("direction")),
                rs.getLong("amount"),
                rs.getLong("balance_after")
        ), transactionId);
    }
}