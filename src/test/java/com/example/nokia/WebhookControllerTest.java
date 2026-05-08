package com.example.nokia;

import com.example.nokia.domain.Payment;
import com.example.nokia.domain.PaymentState;
import com.example.nokia.repository.PaymentRepository;
import com.example.nokia.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookControllerTest extends BaseIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    PaymentService paymentService;

    @Test
    void webhook_invalidSignature_returns400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Stripe-Signature", "invalid-signature");

        ResponseEntity<Void> response = restTemplate.exchange(
                "/stripe/webhook",
                HttpMethod.POST,
                new HttpEntity<>("{}", headers),
                Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void markCompleted_idempotent_doesNotOverwriteTerminalState() {
        Payment payment = new Payment();
        payment.setAmount(new BigDecimal("15.00"));
        payment.setCurrency("EUR");
        payment.setState(PaymentState.COMPLETED);
        payment = paymentRepository.save(payment);
        UUID id = payment.getId();

        // Attempting to mark FAILED on an already COMPLETED payment should be ignored
        paymentService.markFailed(id, "{}");

        Payment reloaded = paymentRepository.findById(id).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(PaymentState.COMPLETED);
    }

    @Test
    void markFailed_idempotent_doesNotOverwriteTerminalState() {
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
