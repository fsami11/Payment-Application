## ADDED Requirements

### Requirement: Payment initiation form
The system SHALL serve an `index.html` page with a form that accepts amount and currency and POSTs to `POST /api/payments`. On success the browser SHALL be redirected to the processing page with the payment ID.

#### Scenario: User submits valid payment form
- **WHEN** a user fills in amount and currency and submits the form
- **THEN** the form POSTs to `/api/payments` and the browser is redirected to `processing.html?id={paymentId}`

### Requirement: Processing and polling page
The system SHALL serve a `processing.html` page that polls `GET /api/payments/{id}` using HTML meta-refresh. When `session_url` is available in the response the page SHALL redirect the browser to that URL.

#### Scenario: Session URL not yet available
- **WHEN** the processing page polls and `session_url` is null
- **THEN** the page refreshes and polls again

#### Scenario: Session URL becomes available
- **WHEN** the processing page polls and `session_url` is present in the response
- **THEN** the browser is immediately redirected to the Stripe hosted checkout page

### Requirement: Success page
The system SHALL serve a `success.html` page that Stripe redirects to after a successful payment. The page SHALL display a confirmation message to the user.

#### Scenario: User lands on success page
- **WHEN** Stripe redirects the user to the success URL after payment confirmation
- **THEN** the user sees a payment success message

### Requirement: Failure page
The system SHALL serve a `failure.html` page that Stripe redirects to after a cancelled or failed session. The page SHALL inform the user and offer a way to try again.

#### Scenario: User lands on failure page
- **WHEN** Stripe redirects the user to the cancel URL after abandoning or failing checkout
- **THEN** the user sees a payment failure message with an option to return to the payment form
