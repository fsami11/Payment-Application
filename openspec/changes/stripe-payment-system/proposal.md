## Why

The assignment requires a reliable payment processing application that receives payment requests, persists their state, and processes them asynchronously. Using Stripe's SDK gives us a production-grade payment service with checkout, webhooks, and idempotency built in — eliminating the need to mock or simulate the external payment service.

## What Changes

- Add simple HTML pages: a payment initiation form (amount and currency), a processing/polling page while the Stripe session is being prepared, and success/failure result pages — the card checkout page itself is hosted entirely by Stripe
- Implement a REST API (Spring Boot) to accept payment requests and serve payment status
- Integrate Stripe Java SDK to create Stripe Checkout Sessions — Stripe hosts the entire payment page, users are redirected there to enter card details
- Persist every payment request and its state in PostgreSQL (state machine: RECEIVED → IN_PROGRESS → COMPLETED / FAILED)
- Use RabbitMQ as the work queue between the API layer and the payment processor workers
- Implement the Transactional Outbox Pattern to guarantee no payment is lost on restart
- Use manual ACK with write-before-ACK to guarantee no Stripe response is lost
- Support horizontal scaling via competing consumers on a shared RabbitMQ queue

## Capabilities

### New Capabilities

- `payment-intake`: REST endpoint receives a payment request, persists it plus an outbox entry atomically, returns 202 with a payment ID
- `payment-status`: REST endpoint returns the current state and Stripe response for a given payment ID
- `outbox-relay`: Scheduled relay reads unpublished outbox rows (FOR UPDATE SKIP LOCKED), publishes to RabbitMQ with publisher confirms, marks rows published
- `payment-processor`: RabbitMQ consumer calls Stripe SDK to create a Checkout Session, persists the session ID and hosted checkout URL, then ACKs the message
- `stripe-webhook`: Webhook endpoint receives Stripe events (checkout.session.completed, checkout.session.expired), verifies the signature, and updates payment state accordingly
- `html-ui`: Static HTML pages — payment initiation form (POST to API), processing/polling page, and success/failure result pages
- `startup-recovery`: On app startup, re-queues payments stuck in IN_PROGRESS or with unpublished outbox entries older than a threshold

### Modified Capabilities

## Impact

- **New dependency**: Stripe Java SDK (`stripe-java`)
- **New dependency**: Spring AMQP (RabbitMQ)
- **New dependency**: Spring Data JPA + PostgreSQL driver
- **Database**: Two new tables — `payments`, `outbox`
- **Infrastructure**: Requires a running PostgreSQL instance and RabbitMQ broker
- **External**: Requires a Stripe account, API key, and webhook signing secret
- **Frontend**: Simple HTML/CSS served as static resources from Spring Boot (no JS framework)
