## Context

The application is a Spring Boot service that must reliably process payments end-to-end. Stripe Checkout handles user-facing payment collection — Stripe hosts the entire card input and payment UI, we never handle card data directly. The frontend is simple HTML served from the same Spring Boot app.

The system must survive restarts without losing payments, must not lose Stripe responses, and must scale horizontally. PostgreSQL persists payment state. RabbitMQ decouples payment intake from Stripe session creation, providing durability across restarts and enabling horizontal scaling via competing consumers.


**Goals:**
- Receive payment requests, persist state immediately, and process each payment exactly once (at-least-once delivery with idempotent consumer)
- Survive application restarts without losing in-flight payments — no payment is ever silently dropped
- Guarantee no Stripe response is ever lost across crashes or restarts
- Scale horizontally: multiple instances share PostgreSQL and RabbitMQ, competing for queue messages without duplication
- Use real Stripe Checkout Sessions — no mocks, no custom card form, no card data on our servers
- Serve simple HTML pages for payment initiation, processing, and result from the same process


## Decisions

### 1. Transactional Outbox Pattern for intake durability

**Decision**: On `POST /payments`, write the `payments` row and an `outbox` row in the same DB transaction. A scheduled relay (every 500ms) reads unpublished outbox rows with `SELECT FOR UPDATE SKIP LOCKED` and publishes to RabbitMQ with publisher confirms before marking them `published = true`.

**Why**: A direct Stripe call after the DB write has a crash window — the payment is in DB but Stripe is never called and no message reaches the queue. The outbox collapses both into one atomic step. The `published` flag is the checkpoint that separates work done from work pending. On restart, the relay finds all rows where `published = false` and re-publishes them — no payment is ever silently dropped.

**Alternative considered**: Direct publish to RabbitMQ inside the same API request — rejected because the DB write and MQ publish are not atomic; a crash between them loses the payment with no recovery path.

---

### 2. Worker creates the Stripe Checkout Session (async)

**Decision**: The RabbitMQ consumer (worker) is responsible for calling `Session.create()` on the Stripe SDK, not the API layer. The worker writes the returned `session.id` and `session.url` to the `payments` row, updates state to `IN_PROGRESS`, then ACKs the message.

**Why**: Doing the Stripe call in the API layer would skip the outbox guarantee — if the app crashes between `Session.create()` returning and the DB write, the Stripe session exists but our system has no record of it. Doing it in the worker keeps the full durability chain intact. The payment UUID is passed as the Stripe idempotency key so re-delivered messages produce the same session.

**UX implication**: The client receives 202 immediately and polls `GET /payments/{id}` on a processing page via HTML meta-refresh. The API reads the `session_url` from DB and returns it. Once `session_url` is not null the page redirects the user to Stripe. The wait is typically under one second.

---

### 3. Stripe Checkout Sessions with idempotency keys

**Decision**: Use `com.stripe.model.checkout.Session.create()` with the payment UUID as the Stripe idempotency key. The session is configured with `success_url` and `cancel_url` pointing back to our app. State transitions are driven entirely by Stripe webhooks.

**Why Checkout Sessions over PaymentIntents**: Checkout Sessions give us Stripe's fully hosted payment page — no card data ever touches our server, no Stripe.js needed in the frontend, PCI compliance handled by Stripe. PaymentIntents are for custom card forms which contradicts the simple HTML requirement.

**Why the user must confirm on Stripe's page**: No money moves at session creation. The financial transaction only happens when the user enters card details and confirms on Stripe's hosted page. Our system creates the session and waits for Stripe to tell us the outcome via webhook.

---

### 4. Write-before-ACK for Stripe response durability

**Decision**: The worker calls `Session.create()`, writes the full Stripe response to the `payments` row, then calls `channel.basicAck()`. Manual ACK mode with `prefetchCount=1` per consumer.

**Why**: If the app crashes after calling Stripe but before writing to DB, the un-ACKed message is re-delivered on reconnect. The worker re-calls Stripe with the same idempotency key — Stripe returns the identical session — worker writes to DB and ACKs. If the app crashes after writing to DB and after ACKing, nothing needs recovery — the session URL is already safe in DB and the processing page will pick it up on the next poll. The crash at that point is harmless.

---

### 5. Webhook processing for final state transitions

**Decision**: `POST /stripe/webhook` receives Stripe events, verifies the signature with the webhook signing secret, updates payment state in DB synchronously, then returns 200 to Stripe.

**Why synchronous over queue-based**: Stripe retries webhooks for up to 72 hours on non-200 responses. This is a stronger durability guarantee than a second internal queue. The DB update must complete before returning 200 — if it fails we return 500 and Stripe retries. Returning 200 before the DB write would let Stripe stop retrying before the state is actually saved.

**Events handled**:
- `checkout.session.completed` → state = `COMPLETED`, store full event payload
- `checkout.session.expired` → state = `FAILED`, store full event payload

---

### 6. Payment state machine

Every payment moves through explicit states persisted in DB:

```
RECEIVED → IN_PROGRESS → COMPLETED
                       → FAILED
```

- `RECEIVED` — set at POST /payments. Payment exists in our system, Stripe does not know yet.
- `IN_PROGRESS` — set by worker after Session.create() succeeds. Stripe has an open Checkout Session. session_url is now available in DB.
- `COMPLETED` — set by webhook handler on checkout.session.completed.
- `FAILED` — set by webhook handler on checkout.session.expired, or after DLQ exhaustion.

**Why explicit states matter**: The worker checks state before calling Stripe — if already `IN_PROGRESS`, `COMPLETED`, or `FAILED` it skips the Stripe call and just ACKs. This makes re-delivery on crash completely safe. The startup recovery job uses state to identify stuck payments. The processing page uses state to know when to redirect.

---

### 7. Startup recovery

On every app startup, a recovery job runs:

- Finds payments in `RECEIVED` state with unpublished outbox entries older than 30 seconds and re-flags them for relay
- Finds payments stuck in `IN_PROGRESS` older than 30 seconds (worker crashed mid-processing) and resets them to `RECEIVED` so the outbox relay re-queues them
- Stripe idempotency keys ensure re-queued payments produce the same session, never a duplicate charge

---

### 8. Horizontal scaling

Multiple instances of the same JAR share one PostgreSQL and one RabbitMQ:

- `SELECT FOR UPDATE SKIP LOCKED` on the outbox relay ensures each outbox row is claimed by exactly one instance — no duplicate publishes
- Competing consumers on `payments.queue` — RabbitMQ delivers each message to exactly one worker across all instances
- `GET /api/payments/{id}` reads from the shared DB — any instance can serve any client's poll request and return the same answer
- Startup recovery uses `SELECT FOR UPDATE SKIP LOCKED` to prevent multiple instances racing on the same stuck payments

---

### 9. Crash safety summary

| Crash point | Recovery mechanism |
|---|---|
| After DB write, before outbox relay publishes | `published = false` row found on restart, relay re-publishes |
| After relay publishes, before worker processes | Message sits in durable RabbitMQ queue, worker picks up on restart |
| After Stripe call, before DB write | Un-ACKed message re-delivered, worker retries with idempotency key |
| After DB write and ACK | session_url already in DB, processing page picks it up on next poll |
| After user confirms, before webhook processed | Stripe retries webhook for 72 hours until our handler returns 200 |

---

### 10. PostgreSQL schema

```sql
payments (
  id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  amount                    NUMERIC(19,4) NOT NULL,
  currency                  VARCHAR(3) NOT NULL,
  state                     VARCHAR(20) NOT NULL,
  stripe_session_id         VARCHAR,
  stripe_session_url        VARCHAR,
  stripe_response_payload   JSONB,
  created_at                TIMESTAMPTZ DEFAULT now(),
  updated_at                TIMESTAMPTZ DEFAULT now()
)

outbox (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  payment_id  UUID NOT NULL REFERENCES payments(id),
  published   BOOLEAN NOT NULL DEFAULT false,
  created_at  TIMESTAMPTZ DEFAULT now()
)
```

Index on `outbox(published) WHERE published = false` for fast relay queries.

---

### 11. RabbitMQ topology

- Exchange: `payments.exchange` (direct, durable)
- Queue: `payments.queue` (durable, dead-letter → `payments.dlq`)
- All messages: `deliveryMode=2` (persistent)
- Publisher confirms enabled on the relay channel
- `prefetchCount=1` per consumer

---

### 12. Static HTML pages

Four pages in `src/main/resources/static/`:

- `index.html` — payment initiation form (amount, currency, submit)
- `processing.html` — polls `GET /payments/{id}` via meta-refresh; redirects to `session_url` once available
- `success.html` — shown after Stripe redirects back on successful payment
- `failure.html` — shown after Stripe redirects back on cancelled or expired session

## Risks / Trade-offs

- **Brief UX wait before redirect to Stripe** — the processing page polls until the worker creates the session. Typically under one second. Acceptable given the durability guarantees it enables.
- **Stripe webhook delivery order not guaranteed** → webhook handler checks current DB state before applying transitions; out-of-order or duplicate events are ignored if state is already terminal.
- **Re-delivery causes duplicate Session.create() calls** → mitigated by Stripe idempotency keys; duplicate calls return the same Session.
- **Stripe session expiry (24h)** → if a payment stays in IN_PROGRESS beyond 24h, the session URL expires. Startup recovery should detect and mark such payments FAILED.
- **Performance testing against real Stripe** → Stripe test mode has rate limits making load testing impractical against the real API. 

