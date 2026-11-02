package com.example.ledgerApi;

import com.example.ledgerApi.adapter.db.AccountRepository;
import com.example.ledgerApi.adapter.msg.PaymentSettledMessage;
import com.example.ledgerApi.adapter.web.dto.LedgerDtos.AccountResponse;
import com.example.ledgerApi.adapter.web.dto.LedgerDtos.EntryRequest;
import com.example.ledgerApi.adapter.web.dto.LedgerDtos.RecordTransactionRequest;
import com.example.ledgerApi.config.RabbitMQConfig;
import com.example.ledgerApi.domain.Account;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ConcurrencyAndMessagingIntegrationTest extends BaseIntegrationTest {

    @Autowired
    RabbitTemplate rabbitTemplate;
    @Autowired
    AccountRepository accountRepository;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void concurrentOppositeTransfersDoNotDeadlockAndKeepIntegrity() throws Exception {
        AccountResponse a = openAccount("EUR", "100.00");
        AccountResponse b = openAccount("EUR", "100.00");
        record(UUID.randomUUID().toString(), new RecordTransactionRequest("fund", List.of(
                new EntryRequest("settlement-eur", "DEBIT", "50.00"),
                new EntryRequest(a.id(), "CREDIT", "50.00")
        )));
        record(UUID.randomUUID().toString(), new RecordTransactionRequest("fund", List.of(
                new EntryRequest("settlement-eur", "DEBIT", "50.00"),
                new EntryRequest(b.id(), "CREDIT", "50.00")
        )));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            jobs.add(() -> postTransfer(a.id(), b.id(), "5.00"));
            jobs.add(() -> postTransfer(b.id(), a.id(), "5.00"));
        }
        List<Future<Integer>> results = pool.invokeAll(jobs);
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        for (Future<Integer> result : results) {
            assertThat(result.get()).isIn(201, 422);
        }
        assertThat(sumOfCurrency("EUR")).isZero();
    }

    @Test
    void concurrentDebitsNeverBreachOverdraft() throws Exception {
        AccountResponse merchant = openAccount("EUR", "0.00");
        AccountResponse payouts = openAccount("EUR", "0.00");
        record(UUID.randomUUID().toString(), new RecordTransactionRequest("fund", List.of(
                new EntryRequest("settlement-eur", "DEBIT", "30.00"),
                new EntryRequest(merchant.id(), "CREDIT", "30.00")
        )));

        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            jobs.add(() -> postTransfer(merchant.id(), payouts.id(), "10.00"));
        }
        List<Future<Integer>> results = pool.invokeAll(jobs);
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        long successes = results.stream().mapToInt(f -> {
            try {
                return f.get() == 201 ? 1 : 0;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).sum();
        assertThat(successes).isEqualTo(3);
        assertThat(balance(merchant.id())).isEqualTo("0.00");
        assertThat(sumOfCurrency("EUR")).isZero();
    }

    @Test
    void paymentSettledIsRecordedOnceAcrossRedeliveryAndAnnounced() {
        AccountResponse merchant = openAccount("EUR", "0.00");
        PaymentSettledMessage message = new PaymentSettledMessage(
                "evt-1", "pay-42", merchant.id(), "125.50", "EUR");
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.PAYMENT_SETTLED_ROUTING_KEY, message);
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.PAYMENT_SETTLED_ROUTING_KEY,
                new PaymentSettledMessage("evt-2", "pay-42", merchant.id(), "125.50", "EUR"));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(balance(merchant.id())).isEqualTo("125.50"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM settled_payment WHERE payment_id = ?", Integer.class, "pay-42"
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_transaction WHERE description = ?", Integer.class, "Settled payment pay-42"
        )).isEqualTo(1);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM outbox WHERE request_id = ? AND published_at IS NOT NULL",
                        Integer.class, "payment:pay-42"
                )).isEqualTo(1));
        assertThat(sumOfCurrency("EUR")).isZero();
    }

    @Test
    void unprocessablePaymentGoesToDlqAndDoesNotBlockTheNextMessage() {
        AccountResponse merchant = openAccount("USD", "0.00");
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.PAYMENT_SETTLED_ROUTING_KEY,
                new PaymentSettledMessage("evt-bad", "pay-bad", "missing-account", "10.00", "USD"));
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.PAYMENT_SETTLED_ROUTING_KEY,
                new PaymentSettledMessage("evt-good", "pay-good", merchant.id(), "10.00", "USD"));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(balance(merchant.id())).isEqualTo("10.00"));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(rabbitTemplate.receive(RabbitMQConfig.PAYMENT_SETTLED_DLQ, 200)).isNotNull());
    }

    private int postTransfer(String from, String to, String amount) {
        return postStatus(
                "/v1/transactions",
                UUID.randomUUID().toString(),
                new RecordTransactionRequest("move", List.of(
                        new EntryRequest(from, "DEBIT", amount),
                        new EntryRequest(to, "CREDIT", amount)
                ))
        );
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
