package com.example.ledgerApi.adapter.db;

import com.example.ledgerApi.domain.*;
import java.time.*;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(Account a) {
        jdbc.update("""
            INSERT INTO account (id, currency, overdraft_limit, unlimited_overdraft, balance, created_at)
            VALUES (?, ?, ?, ?, ?, ?)""",
                a.id(), a.currency().name(), a.overdraftLimit(), a.unlimitedOverdraft(), a.balance(),
                LocalDateTime.ofInstant(a.createdAt(), ZoneOffset.UTC));
    }

    public Optional<Account> findById(String id) {
        return jdbc.query("SELECT * FROM account WHERE id = ?", (rs, i) -> new Account(
                        rs.getString("id"),
                        SupportedCurrency.valueOf(rs.getString("currency")),
                        rs.getLong("overdraft_limit"),
                        rs.getBoolean("unlimited_overdraft"),
                        rs.getLong("balance"),
                        rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)), id)
                .stream().findFirst();
    }
}