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
                          SELECT FOR UPDATE SKIP LOCKED LIMIT 10
                          Publish to RabbitMQ → mark published
                                     │
                          PaymentProcessor (RabbitMQ consumer)
                          StripeGateway.createCheckoutSession()
                          Write session_url to DB → ACK
                                     │
                          Browser polls GET /api/payments/{id}
                          → redirected to Stripe hosted checkout
                                     │
                          Stripe webhook → POST /stripe/webhook
                          Verify signature → update state COMPLETED / FAILED
```

**Stack:** Spring Boot 4 · PostgreSQL · RabbitMQ · Stripe Checkout · Flyway · nginx · Docker Compose

---

## Design Decisions

### 1. Transactional Outbox Pattern

On `POST /api/payments`, a `payments` row and an `outbox` row are written in the same DB transaction. A scheduled relay (every 500ms) reads unpublished outbox rows with `SELECT FOR UPDATE SKIP LOCKED` and publishes to RabbitMQ with publisher confirms before marking them `published = true`.

A direct Stripe or RabbitMQ call after the DB write has a crash window, the payment is saved but no message ever reaches the queue. The outbox collapses both into one atomic step, eliminating that window entirely.

### 2. Async Stripe Session Creation (write-before-ACK)

The RabbitMQ consumer calls `StripeGateway.createCheckoutSession()`, writes the session URL to the DB, then ACKs. If the app crashes after calling Stripe but before the DB write, the un-ACKed message is re-delivered and the worker retries. The payment UUID is the Stripe idempotency key, so re-delivery always returns the same session — never a duplicate charge.

### 3. Stripe Gateway Abstraction

Stripe is called through a `StripeGateway` interface rather than directly. This allows a fake implementation to be swapped in for load testing without touching business logic. Spring's `@Profile` annotation controls which implementation is active at startup.

The same pattern applies to webhook verification via `WebhookVerifier`.

### 4. Stripe Checkout Sessions

PCI compliance handled by Stripe Checkout Sessions through a fully hosted payment page.

### 5. Webhook/Final State

`POST /stripe/webhook` receives Stripe events, verifies the HMAC signature, and updates payment state synchronously before returning 200.

### 6. Payment State Machine

```
RECEIVED → IN_PROGRESS → COMPLETED
                       → FAILED
```

The worker checks state before calling Stripe — if already past `RECEIVED`, it skips and ACKs. This makes re-delivery on crash safe. Startup recovery and the processing page both use state to determine what action to take.

### 7. Startup Recovery

On every startup, a recovery job:
- Finds payments stuck in `IN_PROGRESS` older than 30 seconds (worker crashed mid-processing) and resets them to `RECEIVED` so the outbox relay re-queues them
- Finds `RECEIVED` payments with unpublished outbox entries older than 30 seconds and re-flags them for relay

Stripe idempotency keys ensure re-queued payments produce the same session, never a duplicate charge.

### 8. Horizontal Scaling

Multiple instances share one PostgreSQL and one RabbitMQ. nginx sits in front and round-robins HTTP requests across all instances using Docker's internal DNS.

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
- **Fake Stripe adds 300ms latency** — the `FakeStripeGateway` sleeps for 300ms to simulate realistic Stripe API latency during load tests. Without this, results would be artificially optimistic.

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
- [k6](https://k6.io) (for load testing only — `brew install k6`)

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

In a separate terminal:

```bash
stripe listen --forward-to localhost:8080/stripe/webhook
```

The CLI will print:

```
> Ready! Your webhook signing secret is whsec_abc123... (^C to quit)
```

Copy that `whsec_...` value into your `.env` as `STRIPE_WEBHOOK_SECRET`.

### 4. Run the application

```bash
docker compose up --build
```

Open [http://localhost:8080](http://localhost:8080).

---

## Running Tests

```bash
./mvnw test
```

Tests use Testcontainers — Docker must be running. No external services or `.env` file required; the test suite spins up its own PostgreSQL and RabbitMQ containers with placeholder Stripe keys.

Tests covering:
- Payment intake and atomic outbox creation
- Idempotency — duplicate messages produce the same session, never a duplicate charge
- Outbox relay publishes entries and marks them published
- Startup recovery resets stuck IN_PROGRESS payments
- Webhook invalid signature returns 400
- Terminal state transitions are not overwritten
- Unknown payment ID returns 404

---

## Load Testing

Load tests use [k6](https://k6.io) and a fake Stripe implementation activated via Spring profiles. No real Stripe API calls are made — the `FakeStripeGateway` returns instantly (with a 300ms simulated latency) and `FakeWebhookVerifier` skips HMAC signature checks.

### Run the load test stack

```bash
# Start with 3 app instances and fake Stripe
docker compose -f docker-compose.yml -f docker-compose.loadtest.yml up --build --scale app=3

# In a separate terminal
k6 run k6/load-test.js

# Tear down
docker compose -f docker-compose.yml -f docker-compose.loadtest.yml down
```

To confirm load test mode is active:
```bash
docker compose logs app | grep "loadtest"
# Should show: The following 1 profile is active: "loadtest"
```

### What the script tests

Each k6 virtual user runs this cycle:
1. `POST /api/payments` — create a payment
2. Poll `GET /api/payments/{id}` until state = `IN_PROGRESS`
3. `POST /stripe/webhook` — fire a fake completed webhook
4. Poll `GET /api/payments/{id}` until state = `COMPLETED`

10 virtual users run concurrently for 30 seconds.

---

## Performance Results

### Single instance

```
iterations.....................: 102     3.03/s
iteration_duration avg.........: 3.13s
http_req_duration avg..........: 12.02ms
checks_succeeded...............: 100.00% (408/408)
http_req_failed................: 0.00%
```

### 3 instances (horizontally scaled)

```
iterations.....................: 214     6.87/s
iteration_duration avg.........: 1.42s
http_req_duration avg..........: 14.78ms
checks_succeeded...............: 100.00% (856/856)
http_req_failed................: 0.00%
```

### Observations

- Throughput more than doubled (3.03/s → 6.87/s) with 3 instances
- Average iteration duration halved (3.13s → 1.42s) 
- no lost, duplicated, or corrupted payments under load
- The improvement is ~2.3x rather than 3x because PostgreSQL is the shared bottleneck — all 3 instances write to the same database

### Bottlenecks

**1. Outbox relay holds DB locks during RabbitMQ I/O**

The relay runs a single `@Transactional` that spans both the `SELECT FOR UPDATE SKIP LOCKED` and the RabbitMQ publish confirmation (`waitForConfirmsOrDie(5000)`). Row locks are held for the entire duration of RabbitMQ I/O — up to 5 seconds per message, 50 seconds for a full batch of 10. Under load, if RabbitMQ slows down, lock hold times spike and relay cycles from the same instance start overlapping with uncommitted transactions from the previous cycle. A crash after RabbitMQ confirms but before the transaction commits also leaves rows as `published = false`, causing duplicates on the next cycle.

**Fix:** Decouple locking from publishing using a `claimed_at` timestamp — mark rows as claimed in a short transaction, release the lock, then publish outside the transaction. Locks are held for milliseconds regardless of RabbitMQ speed.

**2. No backoff on Stripe retries**

On failure, the consumer immediately republishes the message with an incremented `x-retry-count` and ACKs the original. With `MAX_RETRIES = 3` and no delay, all three retries fire within milliseconds. If Stripe is rate-limiting or briefly unavailable, the retries hit the same condition in rapid succession, exhaust the count, and the payment is marked `FAILED` before the underlying issue has had any chance to resolve.

**Fix:** Introduce a TTL-based retry queue that dead-letters back to the main queue. On failure, a single `basicNack` routes the message into the retry queue where it waits out the TTL before being redelivered, giving transient conditions time to resolve.

---

## Project Structure

```
src/main/java/com/example/nokia/
├── controller/        # PaymentController, WebhookController
├── service/           # PaymentService, StartupRecoveryJob
├── messaging/         # OutboxRelay, PaymentProcessor
├── gateway/           # StripeGateway, WebhookVerifier, real and fake implementations
├── domain/            # Payment, Outbox, PaymentState
├── dto/               # PaymentRequest, PaymentResponse
├── repository/        # PaymentRepository, OutboxRepository
└── config/            # RabbitMQConfig, StripeConfig

src/main/resources/
├── static/            # index.html, processing.html, success.html, failure.html
└── db/migration/      # Flyway SQL migrations

k6/
└── load-test.js       # k6 load test script

openspec/changes/stripe-payment-system/
├── design.md          # Full architecture and decision log
├── proposal.md        # Change summary and capability list
├── tasks.md           # Implementation task tracker
└── specs/             # Per-capability specs
```
