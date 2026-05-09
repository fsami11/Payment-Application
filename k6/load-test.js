import http from 'k6/http';
import { sleep, check } from 'k6';

export const options = {
  vus: 10,
  duration: '30s',
};

const BASE_URL = 'http://localhost:8080';

export default function () {
  // 1. Create payment
  const createRes = http.post(
    `${BASE_URL}/api/payments`,
    JSON.stringify({ amount: 99.99 }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(createRes, { 'payment created': (r) => r.status === 202 });

  const payment = createRes.json();
  const paymentId = payment.id;

  // 2. Poll until IN_PROGRESS (Stripe session created by processor)
  let inProgress = false;
  for (let i = 0; i < 20; i++) {
    sleep(0.5);
    const statusRes = http.get(`${BASE_URL}/api/payments/${paymentId}`);
    if (statusRes.json().state === 'IN_PROGRESS') {
      inProgress = true;
      break;
    }
  }
  check({ inProgress }, { 'reached IN_PROGRESS': (s) => s.inProgress });

  if (!inProgress) return;

  // 3. Fire fake webhook — sig check is skipped under loadtest profile
  const webhookPayload = JSON.stringify({
    id: `evt_fake_${paymentId}`,
    object: 'event',
    api_version: '2025-05-28.basil',
    type: 'checkout.session.completed',
    data: {
      object: {
        id: `cs_fake_${paymentId}`,
        object: 'checkout.session',
        metadata: { payment_id: paymentId },
        payment_status: 'paid',
      },
    },
  });

  const webhookRes = http.post(
    `${BASE_URL}/stripe/webhook`,
    webhookPayload,
    {
      headers: {
        'Content-Type': 'application/json',
        'Stripe-Signature': 'fake_signature',
      },
    }
  );
  check(webhookRes, { 'webhook accepted': (r) => r.status === 200 });

  // 4. Poll until COMPLETED
  let completed = false;
  for (let i = 0; i < 20; i++) {
    sleep(0.5);
    const statusRes = http.get(`${BASE_URL}/api/payments/${paymentId}`);
    if (statusRes.json().state === 'COMPLETED') {
      completed = true;
      break;
    }
  }
  check({ completed }, { 'payment completed': (s) => s.completed });
}
