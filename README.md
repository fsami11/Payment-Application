# Payment Processing Application

A payment processing service built with Spring Boot, PostgreSQL, RabbitMQ, and Stripe Checkout. Designed to survive restarts without losing payments, guarantee no Stripe response is ever dropped, and scale horizontally.

---

## Architecture Overview

```
Browser → POST /api/payments → PaymentController
                                     │
                             DB transaction:
                             INSERT payments (RECEIVED)
                             INSERT outbox (published=false)
                                     │
                          OutboxRelay (every 500ms)
                          SELECT FOR UPDATE SKIP LOCKED
                          Publish to RabbitMQ → mark published
                                     │
                          PaymentProcessor (RabbitMQ consumer)
                          Session.create() → Stripe
                          Write session_url to DB → ACK
                                     │
                          Browser polls GET /api/payments/{id}
                          → redirected to Stripe hosted checkout
                                     │
                          Stripe webhook → POST /api/webhooks/stripe
                          Verify signature → update state COMPLETED / FAILED
```

**Stack:** Spring Boot 4 · PostgreSQL · RabbitMQ · Stripe Checkout · Flyway · Docker Compose

---

## Design Decisions

### 1. Transactional Outbox Pattern

On `POST /api/payments`, a `payments` row and an `outbox` row are written in the same DB transaction. A scheduled relay (every 500ms) reads unpublished outbox rows with `SELECT FOR UPDATE SKIP LOCKED` and publishes to RabbitMQ with publisher confirms before marking them `published = true`.

A direct Stripe or RabbitMQ call after the DB write has a crash window — the payment is saved but no message ever reaches the queue. The outbox collapses both into one atomic step, eliminating that window entirely.

### 2. Async Stripe Session Creation (write-before-ACK)

The RabbitMQ consumer calls `Session.create()`, writes the session URL to the DB, then ACKs. If the app crashes after calling Stripe but before the DB write, the un-ACKed message is re-delivered and the worker retries. The payment UUID is the Stripe idempotency key, so re-delivery always returns the same session — never a duplicate charge.

### 3. Stripe Checkout Sessions over PaymentIntents

Stripe Checkout Sessions give a fully hosted payment page — no card data ever touches our servers, no Stripe.js required, PCI compliance handled by Stripe. PaymentIntents require a custom card form, which adds frontend complexity and PCI scope.

### 4. Webhook-driven final state

`POST /api/webhooks/stripe` receives Stripe events, verifies the signature, and updates payment state synchronously before returning 200. Stripe retries webhooks for up to 72 hours on non-200 responses — a stronger durability guarantee than an internal queue. Returning 200 before the DB write would let Stripe stop retrying before state is actually saved.

Events handled:
- `checkout.session.completed` → state = `COMPLETED`
- `checkout.session.expired` → state = `FAILED`

### 5. Payment State Machine

```
RECEIVED → IN_PROGRESS → COMPLETED
                       → FAILED
```

The worker checks state before calling Stripe — if already past `RECEIVED`, it skips the call and ACKs. This makes re-delivery on crash safe. Startup recovery and the processing page both use state to determine what action to take.

### 6. Startup Recovery

On every startup, a recovery job:
- Finds payments stuck in `IN_PROGRESS` older than 30 seconds (worker crashed mid-processing) and resets them to `RECEIVED` so the outbox relay re-queues them
- Finds `RECEIVED` payments with unpublished outbox entries older than 30 seconds and re-flags them for relay

Stripe idempotency keys ensure re-queued payments produce the same session, never a duplicate charge.

### 7. Horizontal Scaling

Multiple instances share one PostgreSQL and one RabbitMQ:
- `SELECT FOR UPDATE SKIP LOCKED` on the outbox relay ensures each row is claimed by exactly one instance
- Competing consumers on `payments.queue` — RabbitMQ delivers each message to exactly one worker
- `GET /api/payments/{id}` reads shared DB — any instance can serve any client

---

## Crash Safety

| Crash point | Recovery |
|---|---|
| After DB write, before outbox relay publishes | `published = false` row found on restart, relay re-publishes |
| After relay publishes, before worker processes | Message in durable queue, worker picks up on restart |
| After Stripe call, before DB write | Un-ACKed message re-delivered, worker retries with idempotency key |
| After DB write and ACK | session_url already in DB, processing page picks it up on next poll |
| After user confirms, before webhook processed | Stripe retries webhook for 72 hours |

---

## Trade-offs

- **Brief UX wait before redirect to Stripe** — the processing page polls until the worker creates the session (typically under one second). This is the cost of the durability guarantee.
- **Stripe webhook delivery order not guaranteed** — the webhook handler checks current DB state before applying transitions; out-of-order or duplicate events are ignored if state is already terminal.
- **Re-delivery causes duplicate `Session.create()` calls** — mitigated by Stripe idempotency keys; duplicate calls return the same Session object.
- **Minimum charge of €0.50** — enforced on both the frontend (HTML `min` attribute + JS check) and the backend (`@DecimalMin`). This is Stripe's minimum for EUR.

---

## OpenSpec

This project was specified and implemented using [OpenSpec](https://github.com/Fission-AI/OpenSpec). The full change set lives in `openspec/changes/stripe-payment-system/`.

OpenSpec structured the implementation into discrete, reviewable specs before any code was written:

| Spec | Purpose |
|---|---|
| `payment-intake` | POST /api/payments endpoint and atomic outbox write |
| `outbox-relay` | Scheduled relay with SELECT FOR UPDATE SKIP LOCKED |
| `payment-processor` | RabbitMQ consumer and Stripe session creation |
| `stripe-webhook` | Webhook verification and state transitions |
| `payment-status` | GET /api/payments/{id} polling endpoint |
| `startup-recovery` | Recovery job for stuck payments on restart |
| `html-ui` | Static frontend pages |

The `design.md` in that directory documents all architectural decisions and alternatives considered. The `tasks.md` tracks implementation progress.

---

## Prerequisites

- [Docker](https://www.docker.com/get-started) and Docker Compose
- A [Stripe account](https://stripe.com) (free, test mode is sufficient)
- [Stripe CLI](https://stripe.com/docs/stripe-cli) (for local webhook forwarding)

---

## Setup

### 1. Clone the repository

```bash
git clone <repo-url>
cd Nokia
```

### 2. Configure environment variables

```bash
cp .env.example .env
```

Open `.env` and fill in your Stripe keys:

```
STRIPE_API_KEY=sk_test_...         # Stripe Dashboard → Developers → API keys
STRIPE_WEBHOOK_SECRET=whsec_...    # generated in the next step
```

### 3. Start the Stripe CLI webhook listener

In a terminal, run:

```bash
stripe listen --forward-to localhost:8080/api/webhooks/stripe
```

The CLI will print a line like:

```
> Ready! Your webhook signing secret is whsec_abc123... (^C to quit)
```

Copy that `whsec_...` value into your `.env` as `STRIPE_WEBHOOK_SECRET`.

### 4. Run the application

```bash
docker-compose up --build
```

Open [http://localhost:8080](http://localhost:8080).

---

## Running Tests

```bash
./mvnw test
```

Tests use Testcontainers — Docker must be running. No external services or `.env` file required; the test suite spins up its own PostgreSQL and RabbitMQ containers with placeholder Stripe keys.

There are 13 integration tests covering:
- Payment intake and atomic outbox creation
- Idempotency (duplicate messages produce one session)
- Outbox relay publishes and marks entries as published
- Startup recovery resets stuck IN_PROGRESS payments
- Webhook handling for completed and expired sessions
- Unknown payment ID returns 404

---

## Performance Testing

> Note: Load testing against the real Stripe API is impractical in test mode due to rate limits. The tests below measure the internal system — intake, outbox relay, and queue throughput — with Stripe calls mocked at the worker level.

### Single instance

Run with:
```bash
docker-compose up --build
```

### Horizontally scaled (multiple instances)

Scale the app service:
```bash
docker-compose up --build --scale app=3
```

With a load balancer in front, multiple instances compete on the shared RabbitMQ queue. Each outbox row and each queue message is processed by exactly one instance (`SELECT FOR UPDATE SKIP LOCKED` + RabbitMQ competing consumers).

**Observed behaviour:**
- Intake throughput scales linearly with instances (each instance handles its own HTTP requests)
- Worker throughput scales with instance count (competing consumers share the queue)
- No duplicate Stripe sessions observed under load (idempotency keys)
- No lost payments on instance kill during processing (un-ACKed messages re-delivered)

**Bottleneck:** PostgreSQL becomes the bottleneck before RabbitMQ or the app under high load. Connection pool sizing (`spring.datasource.hikari.maximum-pool-size`) and read replicas for status polling are the recommended next steps for higher throughput.

---

## Project Structure

```
src/main/java/com/example/nokia/
├── controller/        # PaymentController, WebhookController
├── service/           # PaymentService, StartupRecoveryJob
├── messaging/         # OutboxRelay, PaymentProcessor
├── domain/            # Payment, Outbox, PaymentState
├── dto/               # PaymentRequest, PaymentResponse
├── repository/        # PaymentRepository, OutboxRepository
└── config/            # RabbitMQConfig, StripeConfig

src/main/resources/
├── static/            # index.html, processing.html, success.html, failure.html
└── db/migration/      # Flyway SQL migrations

openspec/changes/stripe-payment-system/
├── design.md          # Full architecture and decision log
├── proposal.md        # Change summary and capability list
├── tasks.md           # Implementation task tracker
└── specs/             # Per-capability specs
```
