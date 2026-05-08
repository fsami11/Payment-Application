## ADDED Requirements

### Requirement: Relay unpublished outbox entries to RabbitMQ
The system SHALL run a scheduled relay every 500ms that reads outbox rows where `published = false`, publishes each payment ID to `payments.queue` with publisher confirms, then marks the row `published = true`.

#### Scenario: Unpublished outbox entry exists
- **WHEN** the relay runs and finds rows with `published = false`
- **THEN** each payment ID is published to RabbitMQ, confirmed by the broker, and the outbox row is marked `published = true`

#### Scenario: No unpublished entries
- **WHEN** the relay runs and finds no rows with `published = false`
- **THEN** the relay completes with no action

#### Scenario: RabbitMQ publish fails
- **WHEN** the broker does not confirm a publish
- **THEN** the outbox row remains `published = false` and will be retried on the next relay cycle

### Requirement: Safe concurrent relay across instances
The relay SHALL use `SELECT FOR UPDATE SKIP LOCKED` so that multiple running instances never publish the same outbox row more than once.

#### Scenario: Two instances run relay simultaneously
- **WHEN** two app instances execute the relay at the same time
- **THEN** each outbox row is claimed and published by exactly one instance — no row is published twice
