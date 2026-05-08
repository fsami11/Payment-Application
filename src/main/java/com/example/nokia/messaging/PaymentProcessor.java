package com.example.nokia.messaging;

import com.example.nokia.config.RabbitMQConfig;
import com.example.nokia.domain.Payment;
import com.example.nokia.domain.PaymentState;
import com.example.nokia.service.PaymentService;
import com.rabbitmq.client.Channel;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${stripe.success-url}")
    private String successUrl;

    @Value("${stripe.cancel-url}")
    private String cancelUrl;

    public PaymentProcessor(PaymentService paymentService, RabbitTemplate rabbitTemplate) {
        this.paymentService = paymentService;
        this.rabbitTemplate = rabbitTemplate;
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

        // Idempotency check — skip if already processed
        if (payment.getState() != PaymentState.RECEIVED) {
            log.info("Payment {} already in state {} — ACKing without processing", paymentId, payment.getState());
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
                    .setSuccessUrl(successUrl + "?paymentId=" + paymentId)
                    .setCancelUrl(cancelUrl + "?paymentId=" + paymentId)
                    .addLineItem(SessionCreateParams.LineItem.builder()
                            .setQuantity(1L)
                            .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                    .setCurrency(payment.getCurrency().toLowerCase())
                                    .setUnitAmount(payment.getAmount()
                                            .multiply(java.math.BigDecimal.valueOf(100))
                                            .longValue())
                                    .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                            .setName("Payment")
                                            .build())
                                    .build())
                            .build())
                    .build();

            com.stripe.net.RequestOptions options = com.stripe.net.RequestOptions.builder()
                    .setIdempotencyKey(paymentId.toString())
                    .build();

            Session session = Session.create(params, options);

            // Write to DB before ACK — guarantees no response loss
            paymentService.markInProgress(paymentId, session.getId(), session.getUrl());

            channel.basicAck(deliveryTag, false);
            log.info("Payment {} moved to IN_PROGRESS with session {}", paymentId, session.getId());

        } catch (Exception e) {
            log.error("Failed to process payment {}", paymentId, e);
            int retryCount = getRetryCount(message);

            if (retryCount >= MAX_RETRIES) {
                log.error("Payment {} exhausted retries — sending to DLQ", paymentId);
                paymentService.markFailed(paymentId);
                channel.basicNack(deliveryTag, false, false);
            } else {
                // Re-publish with incremented retry count
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
