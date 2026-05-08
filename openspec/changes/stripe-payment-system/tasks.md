## 1. Project Setup

- [x] 1.1 Add dependencies to pom.xml: `stripe-java`, `spring-boot-starter-amqp`, `spring-boot-starter-data-jpa`, `postgresql` driver, `spring-boot-starter-web`
- [x] 1.2 Configure `application.properties` with PostgreSQL datasource, RabbitMQ connection, and Stripe API key and webhook secret
- [x] 1.3 Configure RabbitMQ topology: declare `payments.exchange`, `payments.queue` (durable, dead-letter → `payments.dlq`), and `payments.dlq`

## 2. Database Schema

- [x] 2.1 Create `payments` table migration: `id`, `amount`, `currency`, `state`, `stripe_session_id`, `stripe_session_url`, `stripe_response_payload`, `created_at`, `updated_at`
- [x] 2.2 Create `outbox` table migration: `id`, `payment_id` (FK), `published`, `created_at`
- [x] 2.3 Add partial index on `outbox(published) WHERE published = false`

## 3. Domain Model and Repository

- [x] 3.1 Create `Payment` JPA entity with all fields and state enum (`RECEIVED`, `IN_PROGRESS`, `COMPLETED`, `FAILED`)
- [x] 3.2 Create `Outbox` JPA entity
- [x] 3.3 Create `PaymentRepository` with query for stuck `IN_PROGRESS` payments (`state = IN_PROGRESS AND stripe_session_url IS NULL AND updated_at < now - 30s`)
- [x] 3.4 Create `OutboxRepository` with query for unpublished entries (`published = false`)

## 4. Payment Intake API

- [x] 4.1 Create `PaymentController` with `POST /api/payments` endpoint
- [x] 4.2 Implement request validation — reject missing or invalid amount/currency with 400
- [x] 4.3 Implement atomic DB write — persist `Payment` (state = `RECEIVED`) and `Outbox` (published = false) in a single `@Transactional` method
- [x] 4.4 Return 202 Accepted with payment ID

## 5. Payment Status API

- [x] 5.1 Add `GET /api/payments/{id}` endpoint to `PaymentController`
- [x] 5.2 Return 200 with payment ID, state, and `session_url` (null if not yet available)
- [x] 5.3 Return 404 if payment ID does not exist

## 6. Outbox Relay

- [x] 6.1 Create `OutboxRelay` scheduled component running every 500ms
- [x] 6.2 Implement `SELECT FOR UPDATE SKIP LOCKED` query to safely claim unpublished outbox rows across multiple instances
- [x] 6.3 Enable publisher confirms on the RabbitMQ channel
- [x] 6.4 Publish each payment ID to `payments.exchange` and wait for broker confirm before marking `published = true`

## 7. Payment Processor (Worker)

- [x] 7.1 Create `PaymentProcessor` with `@RabbitListener` on `payments.queue`, manual ACK mode, `prefetchCount=1`
- [x] 7.2 Implement idempotency check — if payment state is already `IN_PROGRESS`, `COMPLETED`, or `FAILED`, ACK and return immediately
- [x] 7.3 Call `Session.create()` on Stripe SDK with payment UUID as idempotency key, `success_url`, and `cancel_url`
- [x] 7.4 Write `stripe_session_id` and `stripe_session_url` to DB and update state to `IN_PROGRESS`
- [x] 7.5 ACK the message only after successful DB write
- [x] 7.6 On Stripe call failure, NACK the message for retry — after max retries RabbitMQ routes to DLQ and payment state is set to `FAILED`

## 8. Stripe Webhook Handler

- [x] 8.1 Create `WebhookController` with `POST /stripe/webhook` endpoint
- [x] 8.2 Verify Stripe signature using webhook signing secret — return 400 on invalid signature
- [x] 8.3 Handle `checkout.session.completed` — check state is not already terminal, update to `COMPLETED`, store full event payload in `stripe_response_payload`
- [x] 8.4 Handle `checkout.session.expired` — check state is not already terminal, update to `FAILED`, store full event payload in `stripe_response_payload`
- [x] 8.5 Return 200 only after DB write succeeds — return 500 on failure so Stripe retries

## 9. Startup Recovery

- [x] 9.1 Create `StartupRecoveryJob` that runs once on application startup using `@EventListener(ApplicationReadyEvent.class)`
- [x] 9.2 Find payments in `IN_PROGRESS` with `stripe_session_url IS NULL` older than 30 seconds and reset state to `RECEIVED` and outbox entry to `published = false`
- [x] 9.3 Use `SELECT FOR UPDATE SKIP LOCKED` in the recovery query to prevent multiple instances racing on the same payments

## 10. HTML Pages

- [x] 10.1 Create `index.html` — payment initiation form with amount and currency fields, POSTs to `/api/payments`
- [x] 10.2 Create `processing.html` — reads payment ID from URL query param, polls `GET /api/payments/{id}` via HTML meta-refresh, redirects to `session_url` when available
- [x] 10.3 Create `success.html` — payment success confirmation page with link back to `index.html`
- [x] 10.4 Create `failure.html` — payment failure page with link back to `index.html` to try again

## 11. Configuration and Infrastructure

- [x] 11.1 Create `docker-compose.yml` with PostgreSQL and RabbitMQ services
- [x] 11.2 Configure Stripe webhook forwarding for local development using Stripe CLI (`stripe listen --forward-to localhost:8080/stripe/webhook`)
- [x] 11.3 Document environment variables: `STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET`, `SPRING_DATASOURCE_URL`, `SPRING_RABBITMQ_HOST`

## 12. Testing

- [x] 12.1 Write integration test for `POST /api/payments` — verify atomic DB write of payment and outbox row
- [x] 12.2 Write integration test for outbox relay — verify unpublished rows are published and marked `published = true`
- [x] 12.3 Write integration test for payment processor — verify Stripe session is created and state updated to `IN_PROGRESS`
- [x] 12.4 Write integration test for webhook handler — verify state transitions and idempotency on duplicate events
- [x] 12.5 Write restart recovery test — kill app mid-processing, restart, verify payment continues without manual intervention
