package com.example.nokia.messaging;

import com.example.nokia.config.RabbitMQConfig;
import com.example.nokia.domain.Outbox;
import com.example.nokia.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;

    public OutboxRelay(OutboxRepository outboxRepository, RabbitTemplate rabbitTemplate) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void relay() {
        List<Outbox> pending = outboxRepository.findUnpublishedForUpdate();
        if (pending.isEmpty()) return;

        for (Outbox outbox : pending) {
            try {
                rabbitTemplate.invoke(t -> {
                    t.convertAndSend(
                            RabbitMQConfig.EXCHANGE,
                            RabbitMQConfig.ROUTING_KEY,
                            outbox.getPaymentId().toString()
                    );
                    t.waitForConfirmsOrDie(5000);
                    return null;
                });
                outbox.setPublished(true);
                outboxRepository.save(outbox);
                log.debug("Published payment {} to RabbitMQ", outbox.getPaymentId());
            } catch (Exception e) {
                log.error("Failed to publish payment {} to RabbitMQ — will retry", outbox.getPaymentId(), e);
            }
        }
    }
}
