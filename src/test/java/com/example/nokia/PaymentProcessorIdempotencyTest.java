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

        paymentService.markInProgress(id, "sess_test", "https://stripe.com/test");

        Payment reloaded = paymentRepository.findById(id).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(PaymentState.COMPLETED);
        assertThat(reloaded.getStripeSessionUrl()).isNull();
    }

    @Test
    void markFailed_doesNotOverwriteCompletedState() {
        Payment payment = new Payment();
        payment.setAmount(new BigDecimal("15.00"));
        payment.setCurrency("EUR");
        payment.setState(PaymentState.COMPLETED);
        payment = paymentRepository.save(payment);
        UUID id = payment.getId();

        paymentService.markFailed(id, "{}");

        Payment reloaded = paymentRepository.findById(id).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(PaymentState.COMPLETED);
    }

    @Test
    void markCompleted_doesNotOverwriteFailedState() {
        Payment payment = new Payment();
        payment.setAmount(new BigDecimal("15.00"));
        payment.setCurrency("EUR");
        payment.setState(PaymentState.FAILED);
        payment = paymentRepository.save(payment);
        UUID id = payment.getId();

        paymentService.markCompleted(id, "{}");

        Payment reloaded = paymentRepository.findById(id).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(PaymentState.FAILED);
    }
}
