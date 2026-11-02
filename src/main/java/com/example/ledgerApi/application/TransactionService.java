package com.example.ledgerApi.application;

import com.example.ledgerApi.adapter.db.AccountRepository;
import com.example.ledgerApi.adapter.db.TransactionRepository;
import com.example.ledgerApi.adapter.msg.PaymentSettledMessage;
import com.example.ledgerApi.adapter.web.TransactionController;
import com.example.ledgerApi.domain.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public TransactionService(AccountRepository accountRepository, TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    public record TransactionResult(LedgerTransaction transaction, List<Entry> entries) {}

    @Transactional
    public TransactionResult recordTransaction(TransactionController.RecordTransactionRequest request, String requestId) {
        return executeTransactionCore(request, null, requestId);
    }

    // 2. (Reversal)
    @Transactional
    public TransactionResult reverseTransaction(String originalTransactionId, String requestId) {
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

        List<TransactionController.EntryRequest> reversedEntryRequests = originalEntries.stream()
                .map(e -> {
                    EntryDirection flippedDirection = (e.direction() == EntryDirection.DEBIT)
                            ? EntryDirection.CREDIT : EntryDirection.DEBIT;
                    String formattedAmount = Money.format(e.amount(), e.currency());
                    return new TransactionController.EntryRequest(e.accountId(), flippedDirection.name(), formattedAmount);
                })
                .toList();

        var reversalRequest = new TransactionController.RecordTransactionRequest(
                "Reversal of " + originalTransactionId,
                reversedEntryRequests
        );

        return executeTransactionCore(reversalRequest, originalTransactionId, requestId);
    }

    private TransactionResult executeTransactionCore(
            TransactionController.RecordTransactionRequest request,
            String reversesTransactionId,
            String requestId
    ) {
        List<String> sortedAccountIds = request.entries().stream()
                .map(TransactionController.EntryRequest::accountId)
                .distinct()
                .sorted()
                .toList();

        List<Account> lockedAccounts = accountRepository.findAllByIdInWithLock(sortedAccountIds);
        Map<String, Account> accountMap = lockedAccounts.stream()
                .collect(Collectors.toMap(Account::id, a -> a));

        for (String id : sortedAccountIds) {
            if (!accountMap.containsKey(id)) {
                throw new LedgerException(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_ACCOUNT",
                        "Unknown account", "Account " + id + " does not exist", "entries");
            }
        }

        Account firstAccount = accountMap.get(sortedAccountIds.get(0));
        SupportedCurrency currency = firstAccount.currency();

        long totalDebits = 0;
        long totalCredits = 0;

        Map<String, Long> runningBalances = lockedAccounts.stream()
                .collect(Collectors.toMap(Account::id, Account::balance));

        List<EntryDraft> evaluatedEntries = new ArrayList<>();

        for (int i = 0; i < request.entries().size(); i++) {
            var entryReq = request.entries().get(i);
            String pointer = "entries[" + i + "]";

            Account acc = accountMap.get(entryReq.accountId());
            if (!acc.currency().equals(currency)) {
                throw new LedgerException(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENCY_MISMATCH",
                        "Transaction refused", "The accounts are in different currencies", pointer);
            }

            EntryDirection direction;
            try {
                direction = EntryDirection.valueOf(entryReq.direction().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new LedgerException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DIRECTION",
                        "Invalid direction", "Direction must be DEBIT or CREDIT", pointer);
            }

            long minorAmount = Money.toMinor(entryReq.amount(), currency, pointer + ".amount", true);

            if (direction == EntryDirection.DEBIT) {
                totalDebits += minorAmount;
                runningBalances.put(acc.id(), runningBalances.get(acc.id()) - minorAmount);
            } else {
                totalCredits += minorAmount;
                runningBalances.put(acc.id(), runningBalances.get(acc.id()) + minorAmount);
            }

            evaluatedEntries.add(new EntryDraft(acc, direction, minorAmount));
        }

        if (totalDebits != totalCredits) {
            throw new LedgerException(HttpStatus.UNPROCESSABLE_ENTITY, "UNBALANCED_TRANSACTION",
                    "Transaction refused", "Debits and credits don't balance", "entries");
        }

        for (Account acc : lockedAccounts) {
            long newBalance = runningBalances.get(acc.id());
            if (!acc.unlimitedOverdraft() && (newBalance + acc.overdraftLimit() < 0)) {
                throw new LedgerException(HttpStatus.UNPROCESSABLE_ENTITY, "OVERDRAFT_EXCEEDED",
                        "Transaction refused", "Account would go past its overdraft limit", "entries");
            }
        }

        String txId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        LedgerTransaction tx = new LedgerTransaction(txId, request.description(), now, reversesTransactionId);
        transactionRepository.insertTransaction(tx);

        List<Entry> savedEntries = new ArrayList<>();
        for (EntryDraft draft : evaluatedEntries) {
            long newBalance = runningBalances.get(draft.account().id());
            accountRepository.updateBalanceAndLimit(draft.account().id(), newBalance);

            Entry entry = new Entry(null, txId, draft.account().id(), currency, draft.direction(), draft.amount(), newBalance);
            long entryId = transactionRepository.insertEntry(entry);

            savedEntries.add(new Entry(entryId, txId, draft.account().id(), currency, draft.direction(), draft.amount(), newBalance));
        }

        transactionRepository.insertOutbox(txId, requestId, "{\"transactionId\":\"" + txId + "\"}");

        return new TransactionResult(tx, savedEntries);
    }

    @Transactional
    public void processSettledPayment(PaymentSettledMessage message) {
        if (transactionRepository.existsSettledPayment(message.paymentId())) {
            return;
        }

        var entryRequests = List.of(
                new TransactionController.EntryRequest(message.settlementAccountId(), "DEBIT", message.amount().toString()),
                new TransactionController.EntryRequest(message.merchantAccountId(), "CREDIT", message.amount().toString())
        );

        var request = new TransactionController.RecordTransactionRequest(
                "Settled payment " + message.paymentId(),
                entryRequests
        );

        executeTransactionCore(request, null, "SETTLED-" + message.paymentId());

        transactionRepository.insertSettledPayment(message.paymentId(), message.amount().toString(), message.currency().name());
    }

    private record EntryDraft(Account account, EntryDirection direction, long amount) {}
}