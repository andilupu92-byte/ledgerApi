# AI usage

## Did I use AI?

Yes. I used Claude (Anthropic) as a pair-programming and design assistant throughout the challenge.

## What I used it for

- **Requirements analysis and planning:** reading the brief, identifying the hidden requirements (idempotency, concurrency, reliable messaging, exact money) and splitting the work into stages that fit the time limit.
- **Design proposals:** the data model (amounts as `BIGINT` minor units, `UNIQUE` constraint on `reverses_transaction_id`), ordered row locking to avoid A→B / B→A deadlocks, the idempotency table with a canonical request hash, the transactional outbox, and the RFC 9457 error model.
- **Code generation:** the initial project skeleton (`docker-compose.yml`, `Dockerfile`, Flyway migration), the OpenAPI draft, the domain classes, the account and transaction services, the idempotency service, repositories and controllers.
- **Debugging:** Docker/WSL startup problems, MySQL and RabbitMQ container errors, and a Spring bean error.

## How I checked what it produced

- I compiled and ran everything myself and exercised the API manually with Postman (valid cases and the refusal cases from example 5 of the brief).
- I read the code I was given before committing it. Not I reviewed line by line but, I took in general
- I validated the project from a fresh clone with `./run.sh`.


## Where the AI was wrong and I had to fix it

- It used the package `com.example.ledger` in its examples, while my project uses `com.example.ledgerApi`, so I adapted the packages.
- A stereotype annotation ended up on `LedgerException`, which made Spring try to create it as a bean and stopped the application from starting. I removed it and kept `@RestControllerAdvice` on `ApiExceptionHandler` only.
- A MySQL image tag failed to start on my machine (entrypoint error), and I had to pin a different version.


Also I used Cursor (Grok 4.6) to finish an existing Ledger API codebase against the challenge brief.

What the model did:

- Read the PDF and the repo, then listed gaps against the functional and “under pressure” requirements.
- Implemented missing pieces: GET transaction, settlement accounts for USD, payment-settled consumption with DLQ, transactional outbox announcements, idempotency in the same DB transaction as the ledger write, sample-message endpoint, Docker/run script, OpenAPI, README.
- Reworked integration tests so they run against Testcontainers MySQL and RabbitMQ and cover concurrency, retries, redelivery, integrity and atomic refusal.

How I checked it:

- Read the generated Java, SQL and tests myself and compared them to the brief’s examples (balanced payout, reversal-once, JPY scale, overdraft, mixed currency).
- Ran `./mvnw verify` so the guarantees are executed against a real database and broker, not only compiled.
- Kept money on `BigDecimal`/`long` minor units and rejected JSON numbers for amounts.


How I checked what it produced
- I tested in local some endpoints
- I run all the test, some tests return fail, and fix it(in TransactionIntegrationTest, only 2 testes was initial passed)

Instruction files given to the model: this repository, plus the challenge PDF at `Backend Code Challenge — Ledger API.pdf`.
