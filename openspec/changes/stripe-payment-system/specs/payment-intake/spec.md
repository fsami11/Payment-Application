## ADDED Requirements

### Requirement: Accept payment request
The system SHALL expose a `POST /api/payments` endpoint that accepts an amount and currency, persists the payment with state `RECEIVED`, writes an outbox entry in the same DB transaction, and returns 202 Accepted with the payment ID.

#### Scenario: Valid payment request
- **WHEN** a client sends `POST /api/payments` with a valid amount and currency
- **THEN** the system creates a `payments` row with state `RECEIVED` and an `outbox` row with `published = false` in a single transaction, and returns 202 with the payment ID

#### Scenario: Missing required fields
- **WHEN** a client sends `POST /api/payments` with missing amount or currency
- **THEN** the system returns 400 Bad Request and no DB rows are created

### Requirement: Atomic persistence with outbox
The system SHALL write the `payments` row and the `outbox` row in a single database transaction so that both exist or neither exists.

#### Scenario: DB write succeeds
- **WHEN** the payment is persisted successfully
- **THEN** both the `payments` row and the `outbox` row exist in DB with consistent data

#### Scenario: DB write fails mid-transaction
- **WHEN** the DB transaction fails for any reason
- **THEN** neither the `payments` row nor the `outbox` row is committed — no partial state exists
