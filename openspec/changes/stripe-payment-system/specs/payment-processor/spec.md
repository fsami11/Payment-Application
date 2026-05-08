## ADDED Requirements

### Requirement: Consume payment messages and create Stripe Checkout Session
The system SHALL listen on `payments.queue` with manual ACK mode and `prefetchCount=1`. For each message, the worker SHALL read the payment from DB, call `Session.create()` on the Stripe SDK using the payment UUID as the idempotency key, write the returned `session_id` and `session_url` to the `payments` row, update state to `IN_PROGRESS`, then ACK the message.

#### Scenario: Stripe session created successfully
- **WHEN** the worker receives a payment ID from the queue
- **THEN** it calls Stripe, writes `session_id` and `session_url` to DB, updates state to `IN_PROGRESS`, and ACKs the message

#### Scenario: App crashes after Stripe call but before DB write
- **WHEN** the app crashes after `Session.create()` returns but before the DB write completes
- **THEN** the un-ACKed message is re-delivered on restart, the worker calls Stripe again with the same idempotency key, Stripe returns the identical session, the worker writes to DB and ACKs — no session is lost

#### Scenario: Stripe call fails
- **WHEN** `Session.create()` throws an exception
- **THEN** the message is NACKed and re-queued for retry — the payment remains in `RECEIVED` state

### Requirement: Idempotent processing
The worker SHALL check the payment state before calling Stripe. If the state is already `IN_PROGRESS`, `COMPLETED`, or `FAILED`, the worker SHALL skip the Stripe call and ACK the message immediately.

#### Scenario: Message re-delivered for already processed payment
- **WHEN** a re-delivered message arrives for a payment already in `IN_PROGRESS` state
- **THEN** the worker skips the Stripe call, ACKs the message, and the payment state remains unchanged

### Requirement: Dead letter on exhausted retries
After a configurable number of failed retries the message SHALL be moved to `payments.dlq` and the payment state SHALL be updated to `FAILED`.

#### Scenario: Worker repeatedly fails to process a message
- **WHEN** a message has been retried the maximum number of times
- **THEN** RabbitMQ moves it to `payments.dlq` and the payment state is set to `FAILED`
