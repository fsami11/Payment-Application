package com.example.nokia.messaging;

import com.example.nokia.config.RabbitMQConfig;
import com.example.nokia.domain.Payment;
import com.example.nokia.domain.PaymentState;
import com.example.nokia.gateway.StripeGateway;
import com.example.nokia.gateway.StripeSession;
import com.example.nokia.service.PaymentService;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

@Component
public class PaymentProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessor.class);
    private static final int MAX_RETRIES = 3;

    private final PaymentService paymentService;
    private final RabbitTemplate rabbitTemplate;
    private final StripeGateway stripeGateway;

    public PaymentProcessor(PaymentService paymentService, RabbitTemplate rabbitTemplate, StripeGateway stripeGateway) {
        this.paymentService = paymentService;
        this.rabbitTemplate = rabbitTemplate;
        this.stripeGateway = stripeGateway;
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE)
    public void processPayment(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String paymentIdStr = new String(message.getBody(), StandardCharsets.UTF_8)
                .replace("\"", "");
        UUID paymentId = UUID.fromString(paymentIdStr);

        Optional<Payment> optional = paymentService.findById(paymentId);
        if (optional.isEmpty()) {
            log.warn("Payment {} not found — ACKing to discard", paymentId);
            channel.basicAck(deliveryTag, false);
            return;
        }

        Payment payment = optional.get();

        if (payment.getState() != PaymentState.RECEIVED) {
            log.info("Payment {} already in state {} — ACKing without processing", paymentId, payment.getState());
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            StripeSession session = stripeGateway.createCheckoutSession(payment);

            paymentService.markInProgress(paymentId, session.id(), session.url());

            channel.basicAck(deliveryTag, false);
            log.info("Payment {} moved to IN_PROGRESS with session {}", paymentId, session.id());

        } catch (Exception e) {
            log.error("Failed to process payment {}", paymentId, e);
            int retryCount = getRetryCount(message);

            if (retryCount >= MAX_RETRIES) {
                log.error("Payment {} exhausted retries — sending to DLQ", paymentId);
                paymentService.markFailed(paymentId);
                channel.basicNack(deliveryTag, false, false);
            } else {
                MessageProperties props = new MessageProperties();
                props.setHeader("x-retry-count", retryCount + 1);
                Message retryMsg = new Message(message.getBody(), props);
                rabbitTemplate.send(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, retryMsg);
                channel.basicAck(deliveryTag, false);
                log.info("Payment {} re-queued for retry attempt {}", paymentId, retryCount + 1);
            }
        }
    }

    private int getRetryCount(Message message) {
        Object count = message.getMessageProperties().getHeader("x-retry-count");
        return count == null ? 0 : (Integer) count;
    }
}
