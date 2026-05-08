## ADDED Requirements

### Requirement: Receive and verify Stripe webhook events
The system SHALL expose a `POST /stripe/webhook` endpoint that verifies the Stripe signature using the webhook signing secret. Requests with an invalid signature SHALL be rejected with 400.

#### Scenario: Valid signature
- **WHEN** Stripe sends a webhook event with a valid signature
- **THEN** the system processes the event and returns 200

#### Scenario: Invalid signature
- **WHEN** a request arrives at `POST /stripe/webhook` with an invalid or missing signature
- **THEN** the system returns 400 and does not process the event

### Requirement: Update payment state on session completion
The system SHALL update the payment state to `COMPLETED` and store the full event payload in `stripe_response_payload` when a `checkout.session.completed` event is received.

#### Scenario: Payment completed successfully
- **WHEN** Stripe sends `checkout.session.completed` for a payment in `IN_PROGRESS` state
- **THEN** the payment state is updated to `COMPLETED` and the full event payload is stored before returning 200

#### Scenario: DB update fails
- **WHEN** the DB update throws an exception
- **THEN** the system returns 500 so Stripe retries the webhook — the state is never silently lost

### Requirement: Update payment state on session expiry
The system SHALL update the payment state to `FAILED` and store the full event payload when a `checkout.session.expired` event is received.

#### Scenario: Session expired
- **WHEN** Stripe sends `checkout.session.expired` for a payment in `IN_PROGRESS` state
- **THEN** the payment state is updated to `FAILED` and the full event payload is stored before returning 200

### Requirement: Ignore out-of-order and duplicate events
The system SHALL check the current payment state before applying any transition. Events that would move a payment backwards from a terminal state (`COMPLETED` or `FAILED`) SHALL be ignored.

#### Scenario: Duplicate webhook delivery
- **WHEN** the same `checkout.session.completed` event is delivered twice
- **THEN** the second delivery is ignored — state remains `COMPLETED` and 200 is returned

#### Scenario: Out-of-order event arrives after terminal state
- **WHEN** `checkout.session.expired` arrives after the payment is already `COMPLETED`
- **THEN** the event is ignored — state remains `COMPLETED` and 200 is returned
