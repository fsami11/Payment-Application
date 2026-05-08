## ADDED Requirements

### Requirement: Query payment status
The system SHALL expose a `GET /api/payments/{id}` endpoint that reads the current payment state and session URL from DB and returns them as JSON.

#### Scenario: Payment exists
- **WHEN** a client sends `GET /api/payments/{id}` for an existing payment
- **THEN** the system returns 200 with the payment ID, current state, and `session_url` (null if not yet created)

#### Scenario: Payment does not exist
- **WHEN** a client sends `GET /api/payments/{id}` for an unknown ID
- **THEN** the system returns 404 Not Found

### Requirement: Reflect latest state
The system SHALL always return the most recently persisted state from DB — any instance serving the request MUST read from the shared PostgreSQL database.

#### Scenario: State updated by worker on a different instance
- **WHEN** a worker on instance B updates the payment state to `IN_PROGRESS`
- **THEN** a subsequent `GET /api/payments/{id}` served by instance A returns `IN_PROGRESS` and the `session_url`
