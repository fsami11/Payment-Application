package com.example.nokia;

import com.example.nokia.domain.Outbox;
import com.example.nokia.domain.Payment;
import com.example.nokia.domain.PaymentState;
import com.example.nokia.repository.OutboxRepository;
import com.example.nokia.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class OutboxRelayTest extends BaseIntegrationTest {

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    OutboxRepository outboxRepository;

    @Test
    void outboxRelay_publishesUnpublishedEntries() {
        Payment payment = new Payment();
        payment.setAmount(new BigDecimal("10.00"));
        payment.setCurrency("USD");
        payment.setState(PaymentState.RECEIVED);
        payment = paymentRepository.save(payment);

        Outbox outbox = Outbox.forPayment(payment.getId());
        outboxRepository.save(outbox);

        final java.util.UUID outboxId = outbox.getId();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Outbox updated = outboxRepository.findById(outboxId).orElseThrow();
            assertThat(updated.isPublished()).isTrue();
        });
    }
}
