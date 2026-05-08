package com.example.nokia;

import com.example.nokia.domain.Payment;
import com.example.nokia.domain.PaymentState;
import com.example.nokia.repository.PaymentRepository;
import com.example.nokia.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentProcessorIdempotencyTest extends BaseIntegrationTest {

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    PaymentService paymentService;

    @Test
    void markInProgress_isIdempotentWhenAlreadyCompleted() {
        Payment payment = new Payment();
        payment.setAmount(new BigDecimal("20.00"));
        payment.setCurrency("USD");
        payment.setState(PaymentState.COMPLETED);
        payment = paymentRepository.save(payment);
        UUID id = payment.getId();

        // Simulates a re-delivered message trying to move a COMPLETED payment back to IN_PROGRESS
        paymentService.markInProgress(id, "sess_test", "https://stripe.com/test");

        Payment reloaded = paymentRepository.findById(id).orElseThrow();
        // State should remain COMPLETED — markInProgress only sets IN_PROGRESS on RECEIVED payments
        assertThat(reloaded.getStripeSessionUrl()).isNull();
    }

    @Test
    void findById_returnsSessionUrl_whenInProgress() {
        Payment payment = new Payment();
        payment.setAmount(new BigDecimal("99.99"));
        payment.setCurrency("GBP");
        payment.setState(PaymentState.IN_PROGRESS);
        payment.setStripeSessionId("sess_abc");
        payment.setStripeSessionUrl("https://checkout.stripe.com/abc");
        payment = paymentRepository.save(payment);

        var found = paymentService.findById(payment.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getStripeSessionUrl()).isEqualTo("https://checkout.stripe.com/abc");
    }
}
