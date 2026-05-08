## ADDED Requirements

### Requirement: Re-queue payments with unpublished outbox entries on startup
On application startup the system SHALL find all payments in `RECEIVED` state whose outbox entry has `published = false` and is older than 30 seconds, and reset them so the outbox relay picks them up on the next cycle.

#### Scenario: Payment stuck in RECEIVED with unpublished outbox entry
- **WHEN** the app restarts and finds a payment in `RECEIVED` state with `published = false` older than 30 seconds
- **THEN** the outbox relay finds the `published = false` entry on the next cycle and publishes it to RabbitMQ automatically

### Requirement: Reset stuck IN_PROGRESS payments on startup
On application startup the system SHALL find all payments in `IN_PROGRESS` state where `stripe_session_url` is null and the record is older than 30 seconds. These are payments where the worker called Stripe but crashed before writing the session URL to DB. The system SHALL reset their state to `RECEIVED` and reset their outbox entry to `published = false` so they are re-queued and processed again. Payments in `IN_PROGRESS` with a non-null `stripe_session_url` are waiting for a Stripe webhook and SHALL NOT be reset.

#### Scenario: Payment stuck in IN_PROGRESS after worker crash
- **WHEN** the app restarts and finds a payment in `IN_PROGRESS` state with `stripe_session_url IS NULL` older than 30 seconds
- **THEN** the payment state is reset to `RECEIVED`, the outbox entry is reset to `published = false`, and the relay re-queues it — Stripe idempotency keys ensure the same session is returned, never a duplicate

#### Scenario: Payment in IN_PROGRESS with a valid session URL
- **WHEN** the app restarts and finds a payment in `IN_PROGRESS` state with a non-null `stripe_session_url`
- **THEN** the payment is left untouched — it is waiting for a Stripe webhook and does not need recovery

### Requirement: Safe concurrent recovery across instances
The startup recovery job SHALL use `SELECT FOR UPDATE SKIP LOCKED` to ensure multiple instances starting simultaneously do not race on the same stuck payments.

#### Scenario: Two instances start simultaneously
- **WHEN** two app instances run the startup recovery job at the same time
- **THEN** each stuck payment is claimed and reset by exactly one instance — no payment is reset twice
