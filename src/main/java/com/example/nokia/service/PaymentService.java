package com.example.nokia.service;

import com.example.nokia.domain.Outbox;
import com.example.nokia.domain.Payment;
import com.example.nokia.domain.PaymentState;
import com.example.nokia.dto.PaymentRequest;
import com.example.nokia.repository.OutboxRepository;
import com.example.nokia.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OutboxRepository outboxRepository;

    public PaymentService(PaymentRepository paymentRepository, OutboxRepository outboxRepository) {
        this.paymentRepository = paymentRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public Payment createPayment(PaymentRequest request) {
        Payment payment = new Payment();
        payment.setAmount(request.amount());
        payment.setCurrency("EUR");
        payment.setState(PaymentState.RECEIVED);
        payment = paymentRepository.save(payment);

        Outbox outbox = Outbox.forPayment(payment.getId());
        outboxRepository.save(outbox);

        return payment;
    }

    public Optional<Payment> findById(UUID id) {
        return paymentRepository.findById(id);
    }

    @Transactional
    public void markInProgress(UUID paymentId, String sessionId, String sessionUrl) {
        paymentRepository.findById(paymentId).ifPresent(payment -> {
            if (payment.getState() != PaymentState.RECEIVED) return;
            payment.setStripeSessionId(sessionId);
            payment.setStripeSessionUrl(sessionUrl);
            payment.setState(PaymentState.IN_PROGRESS);
            paymentRepository.save(payment);
        });
    }

    @Transactional
    public void markCompleted(UUID paymentId, String responsePayload) {
        paymentRepository.findById(paymentId).ifPresent(payment -> {
            if (payment.getState() == PaymentState.COMPLETED || payment.getState() == PaymentState.FAILED) {
                return;
            }
            payment.setState(PaymentState.COMPLETED);
            payment.setStripeResponsePayload(responsePayload);
            paymentRepository.save(payment);
        });
    }

    @Transactional
    public void markFailed(UUID paymentId, String responsePayload) {
        paymentRepository.findById(paymentId).ifPresent(payment -> {
            if (payment.getState() == PaymentState.COMPLETED || payment.getState() == PaymentState.FAILED) {
                return;
            }
            payment.setState(PaymentState.FAILED);
            payment.setStripeResponsePayload(responsePayload);
            paymentRepository.save(payment);
        });
    }

    @Transactional
    public void markFailed(UUID paymentId) {
        markFailed(paymentId, null);
    }
}
