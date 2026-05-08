package com.example.nokia;

import com.example.nokia.domain.PaymentState;
import com.example.nokia.dto.PaymentRequest;
import com.example.nokia.repository.OutboxRepository;
import com.example.nokia.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentIntakeTest extends BaseIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    OutboxRepository outboxRepository;

    @Test
    void postPayment_createsPaymentAndOutboxAtomically() {
        PaymentRequest request = new PaymentRequest(new BigDecimal("25.00"));

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/payments", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsKey("id");

        String id = response.getBody().get("id").toString();

        assertThat(paymentRepository.findById(java.util.UUID.fromString(id)))
                .isPresent()
                .hasValueSatisfying(p -> {
                    assertThat(p.getState()).isEqualTo(PaymentState.RECEIVED);
                    assertThat(p.getAmount()).isEqualByComparingTo(new BigDecimal("25.00"));
                    assertThat(p.getCurrency()).isEqualTo("EUR");
                });

        assertThat(outboxRepository.findAll())
                .anyMatch(o -> o.getPaymentId().toString().equals(id) && !o.isPublished());
    }

    @Test
    void postPayment_missingAmount_returns400() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/payments",
                Map.of("currency", "USD"),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getPayment_unknownId_returns404() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/payments/" + java.util.UUID.randomUUID(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
