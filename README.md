# Idempotency

Implemented via **@Idempotent** aspect and an **idempotency_key** table. Every incoming request with an Idempotency-Key header has its payload hashed **(SHA-256)** and checked. If a retry occurs, the stored HTTP status and response body are returned immediately without re-executing business logic.

## 📦 Asynchronous Messaging & Reliability (Outbox Pattern & DLQ)

To guarantee reliable event delivery and transactional consistency between the Ledger API and external payment processors, the system implements the **Transactional Outbox Pattern** alongside robust message consumption safeguards:

- **Transactional Outbox**: Domain events are written to the `outbox` table within the same local database transaction as the core ledger operations. A background scheduler (`OutboxRelay`) polls and dispatches these events to RabbitMQ, preventing dual-write issues or lost messages if the message broker is temporarily unavailable.
- **Consumer & Redelivery Protection**: Incoming payment settlement events (`payment-settled-queue`) are processed idempotently. A dedicated `settled_payment` table tracks processed payment IDs to safely handle duplicate message deliveries from the broker.
- **Dead Letter Queue (DLQ)**: Failed or malformed messages are automatically routed to a Dead Letter Queue (`payment-settled-dlq`) via dead-letter exchanges, preventing poison messages from blocking the primary processing pipeline.