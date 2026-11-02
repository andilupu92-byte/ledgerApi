package com.example.ledgerApi.application;

import com.example.ledgerApi.adapter.db.AccountRepository;
import com.example.ledgerApi.adapter.db.TransactionRepository;
import com.example.ledgerApi.adapter.msg.PaymentSettledMessage;
import com.example.ledgerApi.adapter.web.dto.LedgerDtos.EntryRequest;
import com.example.ledgerApi.adapter.web.dto.LedgerDtos.RecordTransactionRequest;
import com.example.ledgerApi.adapter.web.dto.LedgerDtos.TransactionResponse;
import com.example.ledgerApi.domain.Account;
import com.example.ledgerApi.domain.Entry;
import com.example.ledgerApi.domain.EntryDirection;
import com.example.ledgerApi.domain.LedgerException;
import com.example.ledgerApi.domain.LedgerTransaction;
import com.example.ledgerApi.domain.Money;
import com.example.ledgerApi.domain.SupportedCurrency;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public TransactionService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    public record TransactionResult(LedgerTransaction transaction, List<Entry> entries) {
        public TransactionResponse toResponse() {
            return TransactionResponse.of(transaction, entries);
        }
    }

    @Transactional
    public TransactionResult recordTransaction(RecordTransactionRequest request, String idempotencyKey) {
        String hash = idempotencyService.hash(request);
        Optional<IdempotencyService.Replay> replay = idempotencyService.begin(idempotencyKey, "RECORD_TRANSACTION", hash);
        if (replay.isPresent()) {
            return replayResult(replay.get());
        }
        TransactionResult result = executeTransactionCore(request, null, idempotencyKey);
        idempotencyService.complete(idempotencyKey, 201, result.toResponse());
        return result;
    }

    @Transactional
    public TransactionResult reverseTransaction(String originalTransactionId, String idempotencyKey) {
        String hash = idempotencyService.hash(Map.of("reverses", originalTransactionId));
        Optional<IdempotencyService.Replay> replay = idempotencyService.begin(idempotencyKey, "REVERSE_TRANSACTION", hash);
        if (replay.isPresent()) {
            return replayResult(replay.get());
        }

        LedgerTransaction originalTx = transactionRepository.findTransactionById(originalTransactionId)
                .orElseThrow(() -> new LedgerException(HttpStatus.NOT_FOUND, "UNKNOWN_TRANSACTION",
                        "Unknown transaction", "Transaction " + originalTransactionId + " does not exist", null));

        if (transactionRepository.existsByReversesTransactionId(originalTransactionId)) {
            throw new LedgerException(HttpStatus.UNPROCESSABLE_ENTITY, "ALREADY_REVERSED",
                    "Transaction refused", "Transaction has already been reversed", null);
        }
        if (originalTx.reversesTransactionId() != null) {
            throw new LedgerException(HttpStatus.UNPROCESSABLE_ENTITY, "CANNOT_REVERSE_REVERSAL",
                    "Transaction refused", "A reversal transaction cannot be reversed", null);
        }

        List<Entry> originalEntries = transactionRepository.findEntriesByTransactionId(originalTransactionId);
        List<EntryRequest> reversedEntryRequests = originalEntries.stream()
                .map(e -> {
                    EntryDirection flipped = e.direction() == EntryDirection.DEBIT
                            ? EntryDirection.CREDIT : EntryDirection.DEBIT;
                    return new EntryRequest(e.accountId(), flipped.name(), Money.format(e.amount(), e.currency()));
                })
                .toList();

        var reversalRequest = new RecordTransactionRequest(
                "Reversal of " + originalTransactionId,
                reversedEntryRequests
        );
        TransactionResult result = executeTransactionCore(reversalRequest, originalTransactionId, idempotencyKey);
        idempotencyService.complete(idempotencyKey, 201, result.toResponse());
        return result;
    }

    @Transactional(readOnly = true)
    public TransactionResult get(String id) {
        LedgerTransaction tx = transactionRepository.findTransactionById(id)
                .orElseThrow(() -> new LedgerException(HttpStatus.NOT_FOUND, "UNKNOWN_TRANSACTION",
                        "Unknown transaction", "Transaction " + id + " does not exist", null));
        return new TransactionResult(tx, transactionRepository.findEntriesByTransactionId(id));
    }

    @Transactional
    public void processSettledPayment(PaymentSettledMessage message) {
        MDC.put("paymentId", message.paymentId());
        MDC.put("eventId", message.eventId());
        try {
            if (message.eventId() == null || message.eventId().isBlank()
                    || message.paymentId() == null || message.paymentId().isBlank()
                    || message.merchantAccountId() == null || message.merchantAccountId().isBlank()
                    || message.amount() == null || message.currency() == null) {
                throw new LedgerException(HttpStatus.UNPROCESSABLE_ENTITY, "MALFORMED_PAYMENT",
                        "Malformed payment-settled message",
                        "eventId, paymentId, merchantAccountId, amount and currency are required",
                        null);
            }

            SupportedCurrency currency = SupportedCurrency.parse(message.currency(), "currency");
            String settlementAccountId = "settlement-" + currency.name().toLowerCase();
            String requestHash = sha256(message.paymentId() + "|" + message.merchantAccountId()
                    + "|" + message.amount() + "|" + currency.name());

            try {
                transactionRepository.claimSettledPayment(message.paymentId(), message.eventId(), requestHash);
            } catch (DuplicateKeyException ex) {
                Optional<String> existing = transactionRepository.findSettledPaymentForUpdate(message.paymentId());
                if (existing.isPresent() && existing.get() != null) {
                    log.info("Ignoring redelivered payment {} (event {})", message.paymentId(), message.eventId());
                    return;
                }
            }

            var request = new RecordTransactionRequest(
                    "Settled payment " + message.paymentId(),
                    List.of(
                            new EntryRequest(settlementAccountId, "DEBIT", message.amount()),
                            new EntryRequest(message.merchantAccountId(), "CREDIT", message.amount())
                    )
            );

            TransactionResult result = executeTransactionCore(request, null, "payment:" + message.paymentId());
            transactionRepository.attachSettledPayment(message.paymentId(), result.transaction().id());
        } finally {
            MDC.remove("paymentId");
            MDC.remove("eventId");
        }
    }

    private TransactionResult executeTransactionCore(
            RecordTransactionRequest request,
            String reversesTransactionId,
            String requestId
    ) {
        if (request.entries() == null || request.entries().size() < 2) {
            throw new LedgerException(HttpStatus.UNPROCESSABLE_ENTITY, "TOO_FEW_ENTRIES",
                    "Transaction refused", "A transaction needs at least two entries", "entries");
        }

        List<String> sortedAccountIds = request.entries().stream()
                .map(EntryRequest::accountId)
                .distinct()
                .sorted()
                .toList();

        List<Account> lockedAccounts = accountRepository.findAllByIdInWithLock(sortedAccountIds);
        Map<String, Account> accountMap = lockedAccounts.stream()
                .collect(Collectors.toMap(Account::id, a -> a));

        for (int i = 0; i < request.entries().size(); i++) {
            String id = request.entries().get(i).accountId();
            if (!accountMap.containsKey(id)) {
                throw new LedgerException(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_ACCOUNT",
                        "Unknown account", "Account " + id + " does not exist", "entries[" + i + "].accountId");
            }
        }

        Account firstAccount = accountMap.get(sortedAccountIds.get(0));
        SupportedCurrency currency = firstAccount.currency();

        long totalDebits = 0;
        long totalCredits = 0;
        Map<String, Long> runningBalances = lockedAccounts.stream()
                .collect(Collectors.toMap(Account::id, Account::balance, (a, b) -> a, LinkedHashMap::new));

        List<EntryDraft> evaluatedEntries = new ArrayList<>();

        for (int i = 0; i < request.entries().size(); i++) {
            var entryReq = request.entries().get(i);
            String pointer = "entries[" + i + "]";
            Account acc = accountMap.get(entryReq.accountId());

            if (!acc.currency().equals(currency)) {
                throw new LedgerException(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENCY_MISMATCH",
                        "Transaction refused", "The accounts are in different currencies", pointer + ".accountId");
            }

            EntryDirection direction;
            try {
                direction = EntryDirection.valueOf(entryReq.direction().toUpperCase());
            } catch (RuntimeException e) {
                throw new LedgerException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DIRECTION",
                        "Invalid direction", "Direction must be DEBIT or CREDIT", pointer + ".direction");
            }

            long minorAmount = Money.toMinor(entryReq.amount(), currency, pointer + ".amount", true);

            long newBalance = direction == EntryDirection.DEBIT
                    ? runningBalances.get(acc.id()) - minorAmount
                    : runningBalances.get(acc.id()) + minorAmount;
            runningBalances.put(acc.id(), newBalance);

            if (direction == EntryDirection.DEBIT) {
                totalDebits += minorAmount;
            } else {
                totalCredits += minorAmount;
            }

            evaluatedEntries.add(new EntryDraft(acc, direction, minorAmount, newBalance));
        }

        if (totalDebits != totalCredits) {
            throw new LedgerException(HttpStatus.UNPROCESSABLE_ENTITY, "UNBALANCED_TRANSACTION",
                    "Transaction refused", "Debits and credits don't balance", "entries");
        }

        for (Account acc : lockedAccounts) {
            long newBalance = runningBalances.get(acc.id());
            if (!acc.unlimitedOverdraft() && (newBalance + acc.overdraftLimit() < 0)) {
                throw new LedgerException(HttpStatus.UNPROCESSABLE_ENTITY, "OVERDRAFT_EXCEEDED",
                        "Transaction refused",
                        "Account " + acc.id() + " would go past its overdraft limit",
                        "entries");
            }
        }

        String txId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        LedgerTransaction tx = new LedgerTransaction(txId, request.description(), now, reversesTransactionId);
        transactionRepository.insertTransaction(tx);

        List<Entry> savedEntries = new ArrayList<>();
        for (EntryDraft draft : evaluatedEntries) {
            Entry entry = new Entry(null, txId, draft.account().id(), draft.account().currency(),
                    draft.direction(), draft.amount(), draft.balanceAfter());
            long entryId = transactionRepository.insertEntry(entry);
            savedEntries.add(new Entry(entryId, txId, draft.account().id(), draft.account().currency(),
                    draft.direction(), draft.amount(), draft.balanceAfter()));
        }

        for (Account acc : lockedAccounts) {
            accountRepository.updateBalance(acc.id(), runningBalances.get(acc.id()));
        }

        transactionRepository.insertOutbox(txId, requestId, writePayload(tx, savedEntries, requestId));
        return new TransactionResult(tx, savedEntries);
    }

    private TransactionResult replayResult(IdempotencyService.Replay replay) {
        TransactionResponse response = objectMapper.readValue(replay.body(), TransactionResponse.class);
        return get(response.id());
    }

    private String writePayload(LedgerTransaction tx, List<Entry> entries, String requestId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", "transaction.recorded");
        payload.put("transactionId", tx.id());
        payload.put("description", tx.description());
        payload.put("recordedAt", tx.recordedAt().toString());
        payload.put("reversesTransactionId", tx.reversesTransactionId());
        payload.put("requestId", requestId);
        payload.put("entries", entries.stream().map(e -> Map.of(
                "accountId", e.accountId(),
                "direction", e.direction().name(),
                "amount", Money.format(e.amount(), e.currency()),
                "balanceAfter", Money.format(e.balanceAfter(), e.currency()),
                "currency", e.currency().name()
        )).toList());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise outbox payload", e);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record EntryDraft(Account account, EntryDirection direction, long amount, long balanceAfter) {}
}
