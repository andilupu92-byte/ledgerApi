package com.example.ledgerApi.adapter.db;

import com.example.ledgerApi.domain.Account;
import com.example.ledgerApi.domain.SupportedCurrency;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Account> ACCOUNT_MAPPER = (rs, i) -> new Account(
            rs.getString("id"),
            SupportedCurrency.valueOf(rs.getString("currency")),
            rs.getLong("overdraft_limit"),
            rs.getBoolean("unlimited_overdraft"),
            rs.getLong("balance"),
            rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)
    );

    public void insert(Account a) {
        jdbc.update("""
            INSERT INTO account (id, currency, overdraft_limit, unlimited_overdraft, balance, created_at)
            VALUES (?, ?, ?, ?, ?, ?)""",
                a.id(), a.currency().name(), a.overdraftLimit(), a.unlimitedOverdraft(), a.balance(),
                LocalDateTime.ofInstant(a.createdAt(), ZoneOffset.UTC));
    }

    public Optional<Account> findById(String id) {
        return jdbc.query("SELECT * FROM account WHERE id = ?", ACCOUNT_MAPPER, id).stream().findFirst();
    }

    public List<Account> findAllByIdInWithLock(List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT * FROM account WHERE id IN (" + placeholders + ") ORDER BY id FOR UPDATE";
        return jdbc.query(sql, ACCOUNT_MAPPER, ids.toArray());
    }

    public void updateBalance(String id, long newBalance) {
        jdbc.update("UPDATE account SET balance = ? WHERE id = ?", newBalance, id);
    }

    public List<Account> findAll() {
        return jdbc.query("SELECT * FROM account", ACCOUNT_MAPPER);
    }
}
