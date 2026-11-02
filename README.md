# Ledger API

A small double-entry ledger: money lives in accounts and moves only through immutable, balanced transactions. A mistake is corrected by recording a reversal, never by changing the original.

Stack: Java 17, Spring Boot 4, MySQL 8, RabbitMQ, Flyway, JDBC.

## Run

You need Docker, Git and a JDK 17+. From the repo root:

```bash
chmod +x mvnw run.sh
./run.sh
```

That script runs the tests (Testcontainers starts MySQL and RabbitMQ), then builds and starts the service with Compose.

- API: http://localhost:8080
- RabbitMQ UI: http://localhost:15672 (guest/guest)

On Windows, run the same script from Git Bash.

## API

Versioned under `/v1`. Amounts are **decimal strings** in major units (`"125.50"`), never JSON numbers, so `0.10 + 0.20` is exactly `0.30`. Errors are RFC 9457 problem details with a stable `code` and `errors[].pointer` for the faulty field.

`Idempotency-Key` is required on every mutating request.

The contract is in [`docs/openapi.yaml`](src/main/resources/static/openapi.yaml).

### Sample requests

Open a merchant account:

```bash
curl -s -X POST http://localhost:8080/v1/accounts \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: open-merchant-42' \
  -d '{"currency":"EUR","overdraftLimit":"0.00"}'
```

Record the payout from the challenge (after opening `payouts` and `fees` the same way, and after a settlement has credited the merchant):

```bash
curl -s -X POST http://localhost:8080/v1/transactions \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: payout-1' \
  -d '{
    "description": "Payout with fee",
    "entries": [
      {"accountId":"<merchant-id>","direction":"DEBIT","amount":"100.00"},
      {"accountId":"<payouts-id>","direction":"CREDIT","amount":"98.50"},
      {"accountId":"<fees-id>","direction":"CREDIT","amount":"1.50"}
    ]
  }'
```

Read an account or a transaction:

```bash
curl -s http://localhost:8080/v1/accounts/<id>
curl -s http://localhost:8080/v1/transactions/<id>
```

Reverse a transaction (once; a reversal cannot be reversed):

```bash
curl -s -X POST http://localhost:8080/v1/transactions/<id>/reversal \
  -H 'Idempotency-Key: reverse-payout-1'
```

### Sample payment-settled message

The payment system publishes to exchange `ledger.exchange` with routing key `payment.settled`. For local use the service also accepts:

```bash
curl -s -X POST http://localhost:8080/v1/debug/payment-settled \
  -H 'Content-Type: application/json' \
  -d '{
    "eventId": "evt-1",
    "paymentId": "pay-42",
    "merchantAccountId": "<merchant-id>",
    "amount": "125.50",
    "currency": "EUR"
  }'
```

That records a transaction debiting `settlement-eur` and crediting the merchant, matching example 1.

Built-in settlement accounts (unlimited overdraft): `settlement-eur`, `settlement-usd`, `settlement-jpy`.

## Design

### Domain

- **Accounts** hold a currency (EUR/USD/JPY with 2/2/0 fraction digits), an overdraft limit (zero unless set), and a balance in integer minor units.
- **Transactions** have two or more entries. Debits decrease a balance, credits increase it. Debits and credits must sum to the same total, all accounts must exist and share one currency, and no account may pass its overdraft.
- Recording is all-or-nothing in one MySQL transaction. Rows in `ledger_transaction` and `entry` are never updated or deleted.
- A reversal inserts a new transaction with every direction flipped and `reverses_transaction_id` set. The unique index on that column enforces “reversed only once”.

### Layout

Ports-and-adapters without a heavy framework:

- `domain` — money, accounts, exceptions; no Spring
- `application` — recording, reversals, settlement, outbox relay
- `adapter.web` / `adapter.db` / `adapter.msg` — HTTP, JDBC, RabbitMQ

JDBC is used instead of JPA so locking (`SELECT … FOR UPDATE`), checks and the outbox stay explicit.

### Concurrency

Accounts touched by a transaction are locked in **sorted id order** with `FOR UPDATE`. Opposite transfers `A → B` and `B → A` take locks in the same order and cannot deadlock. Overdraft checks use those locked balances, so two concurrent debits cannot both sneak past a limit.

### Idempotency

Mutating HTTP calls take `Idempotency-Key`. The key is inserted in the **same database transaction** as the ledger write. A retry on another instance blocks on the unique primary key until the first attempt commits, then returns the same resource. A different body with the same key is `409 IDEMPOTENCY_KEY_REUSED`.

### Payments and redelivery

`payment_id` is the business key. The consumer claims it before recording. A redelivery, including one with a new `event_id`, is ignored once the payment is attached to a transaction.

Malformed or unprocessable messages are logged, rejected without requeue, and land on `payment-settled-dlq`. They do not block the rest of the queue.

### Announcing recorded transactions

Every successful record (reversals included) writes a row to `outbox` in the same transaction. `OutboxRelay` polls unpublished rows with `FOR UPDATE SKIP LOCKED` and publishes to `ledger.exchange` / `transaction.recorded` (queue `transaction-recorded-queue`).

What a consumer can rely on:

- **At-least-once.** If the process crashes after RabbitMQ accepts the message and before `published_at` is set, the row is published again. Consumers must be idempotent on `transactionId`.
- **No silent loss after commit.** If the service dies after the ledger commit, the outbox row is still there and another instance will publish it.
- **Order.** One instance publishes in outbox id order. Several instances do not offer a global total order; consumers should not assume consecutive ids arrive consecutively.
- **Crashes before commit.** Nothing is recorded and nothing is announced.

Logs include `requestId` (the idempotency key) and, for payments, `paymentId` / `eventId`, which are also present on the announcement as `requestId`.

### Integrity

Balances are `BIGINT` minor units. A MySQL check constraint backs overdraft on the account row. At any moment, for each currency, the sum of balances is zero if every transaction was balanced and settlement accounts are included.

## Tests

`./mvnw verify` runs unit tests for exact money and integration tests against real MySQL and RabbitMQ (Testcontainers): happy paths from the brief, each refusal, atomic rollback, idempotent retries, concurrent opposite transfers, concurrent overdraft, payment redelivery, DLQ, and currency-sum integrity.

## Improvements

Before production I would:

- Persist refused HTTP responses under the idempotency key (today a retried failure is re-evaluated against current balances).
- Replace the polling outbox with CDC or a transactional message outbox library, and add a published-event consumer lag metric.
- Restrict `/v1/debug/**` and put a real authn/authz layer in front of the API.
- Add paged account entries (newest first, with `balanceAfter`) and a point-in-time balance, which the brief lists as nice-to-have and I skipped to keep the core guarantees small and tested.
- Schema-evolve the API with additive fields only; keep problem `code` values stable.
- Move settlement account ids out of hardcoded strings into configuration.
- Add OpenTelemetry traces so one payment id is visible from HTTP/AMQP through MySQL to the announcement.
- I would adopt `JPA`, `Spring Data`, `entities` and `Lombok` for better readability
- I would put more effort into packaging; I don't think the classes are well organized and distributed across the project yet
- I would work more on naming (packages, variables, classes)
- I would introduce `Sonar`, `Grafana` and a `ci.yml for CI/CD on GitHub` (running the tests, a Sonar scan and `Dependabot` after each commit)


- Skipped on purpose: JPA, a custom account-id scheme, multi-currency transactions, and reversing a reversal.
- In the future, I will make sure to read the requirements more carefully, as I rushed through them this time.
- I also had some issues with my GitHub account (I created a new one today because, for some reason, I couldn't figure out what happened to my old one). I started to panic a bit because time was running out and I felt like I hadn't made much progress.
- I didn't manage to test all the APIs. For example, I didn't have MySQL installed on my PC, so I couldn't verify whether the data was being inserted correctly into the database. I decided not to install it due to the limited time and because I'm having some issues with my C: drive, which is almost full.
- Move secrets out of the repository: the database and broker credentials are demo defaults
  (`ledger/ledger`, `guest/guest`) committed in `docker-compose.yml` and `application.yml`.
  Before production I would read them from environment variables or a secrets manager
  (Docker/Kubernetes secrets, Vault, AWS Secrets Manager), keep a `.env.example` in the repo while
  the real `.env` stays in `.gitignore`, use a dedicated least-privilege database user instead of
  root, rotate credentials regularly, and scan the repository for leaked secrets in CI
  (for example gitleaks).
- Protect sensitive data in transit, at rest and in logs: TLS for the API, MySQL and the broker;
  encryption at rest; and make sure logs never contain credentials or personal data. The debug
  endpoint for sample payment-settled messages would be disabled or secured outside demo use.