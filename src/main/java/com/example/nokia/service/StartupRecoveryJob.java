package com.example.nokia.service;

import com.example.nokia.domain.Payment;
import com.example.nokia.domain.PaymentState;
import com.example.nokia.repository.OutboxRepository;
import com.example.nokia.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Component
public class StartupRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(StartupRecoveryJob.class);
    private static final int STUCK_THRESHOLD_SECONDS = 30;

    private final PaymentRepository paymentRepository;
    private final OutboxRepository outboxRepository;

    public StartupRecoveryJob(PaymentRepository paymentRepository, OutboxRepository outboxRepository) {
        this.paymentRepository = paymentRepository;
        this.outboxRepository = outboxRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recover() {
        recoverStuckInProgress();
    }

    private void recoverStuckInProgress() {
        OffsetDateTime threshold = OffsetDateTime.now().minusSeconds(STUCK_THRESHOLD_SECONDS);
        List<Payment> stuck = paymentRepository.findStuckInProgress(threshold);

        if (stuck.isEmpty()) {
            log.info("Startup recovery: no stuck payments found");
            return;
        }

        log.warn("Startup recovery: found {} stuck IN_PROGRESS payment(s) — resetting", stuck.size());

        for (Payment payment : stuck) {
            payment.setState(PaymentState.RECEIVED);
            paymentRepository.save(payment);
            outboxRepository.resetPublishedForPayment(payment.getId());
            log.info("Reset stuck payment {} back to RECEIVED", payment.getId());
        }
    }
}
