package com.example.nokia;

import com.example.nokia.domain.Outbox;
import com.example.nokia.domain.Payment;
import com.example.nokia.domain.PaymentState;
import com.example.nokia.repository.OutboxRepository;
import com.example.nokia.repository.PaymentRepository;
import com.example.nokia.service.StartupRecoveryJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class StartupRecoveryTest extends BaseIntegrationTest {

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    OutboxRepository outboxRepository;

    @Autowired
    StartupRecoveryJob startupRecoveryJob;

    @Test
    void recovery_resetsStuckInProgressPayments() throws Exception {
        // Simulate a payment stuck in IN_PROGRESS with no session URL (worker crashed)
        Payment payment = new Payment();
        payment.setAmount(new BigDecimal("50.00"));
        payment.setCurrency("USD");
        payment.setState(PaymentState.IN_PROGRESS);
        payment = paymentRepository.save(payment);

        // Manually backdate updated_at to simulate it being stuck for > 30s
        paymentRepository.updateUpdatedAt(payment.getId(),
                OffsetDateTime.now().minusMinutes(2));

        Outbox outbox = Outbox.forPayment(payment.getId());
        outbox.setPublished(true);
        outboxRepository.save(outbox);

        startupRecoveryJob.recover();

        Payment recovered = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(recovered.getState()).isEqualTo(PaymentState.RECEIVED);

        java.util.UUID savedId = payment.getId();
        Outbox resetOutbox = outboxRepository.findAll().stream()
                .filter(o -> o.getPaymentId().equals(savedId))
                .findFirst()
                .orElseThrow();
        assertThat(resetOutbox.isPublished()).isFalse();
    }

    @Test
    void recovery_doesNotResetInProgressWithSessionUrl() {
        // Payment in IN_PROGRESS with a valid session URL — waiting for webhook, should NOT be reset
        Payment payment = new Payment();
        payment.setAmount(new BigDecimal("30.00"));
        payment.setCurrency("GBP");
        payment.setState(PaymentState.IN_PROGRESS);
        payment.setStripeSessionUrl("https://checkout.stripe.com/test");
        payment = paymentRepository.save(payment);

        startupRecoveryJob.recover();

        Payment reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(PaymentState.IN_PROGRESS);
    }
}
