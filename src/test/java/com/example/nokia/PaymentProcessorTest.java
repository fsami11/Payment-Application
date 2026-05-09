package com.example.nokia;

import com.example.nokia.config.RabbitMQConfig;
import com.example.nokia.domain.Payment;
import com.example.nokia.domain.PaymentState;
import com.example.nokia.gateway.StripeGateway;
import com.example.nokia.gateway.StripeSession;
import com.example.nokia.messaging.PaymentProcessor;
import com.example.nokia.service.PaymentService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentProcessorTest {

    @Mock PaymentService paymentService;
    @Mock RabbitTemplate rabbitTemplate;
    @Mock Channel channel;
    @Mock StripeGateway stripeGateway;

    PaymentProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new PaymentProcessor(paymentService, rabbitTemplate, stripeGateway);
    }

    private Message messageFor(UUID paymentId) {
        return messageFor(paymentId, null);
    }

    private Message messageFor(UUID paymentId, Integer retryCount) {
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(1L);
        if (retryCount != null) props.setHeader("x-retry-count", retryCount);
        byte[] body = ("\"" + paymentId + "\"").getBytes(StandardCharsets.UTF_8);
        return new Message(body, props);
    }

    private Payment stubReceivedPayment(UUID paymentId) {
        Payment payment = mock(Payment.class);
        when(payment.getState()).thenReturn(PaymentState.RECEIVED);
        when(payment.getAmount()).thenReturn(new BigDecimal("25.00"));
        when(payment.getCurrency()).thenReturn("EUR");
        when(paymentService.findById(paymentId)).thenReturn(Optional.of(payment));
        return payment;
    }

    @Test
    void processPayment_receivedPayment_createsStripeSessionAndAcks() throws Exception {
        UUID paymentId = UUID.randomUUID();
        Payment payment = stubReceivedPayment(paymentId);
        when(payment.getId()).thenReturn(paymentId);

        StripeSession session = new StripeSession("sess_test123", "https://checkout.stripe.com/test");
        when(stripeGateway.createCheckoutSession(payment)).thenReturn(session);

        processor.processPayment(messageFor(paymentId), channel);

        verify(paymentService).markInProgress(paymentId, "sess_test123", "https://checkout.stripe.com/test");
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void processPayment_paymentNotFound_acksAndDiscards() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(paymentService.findById(paymentId)).thenReturn(Optional.empty());

        processor.processPayment(messageFor(paymentId), channel);

        verify(channel).basicAck(1L, false);
        verify(paymentService, never()).markInProgress(any(), any(), any());
        verify(rabbitTemplate, never()).send(any(String.class), any(String.class), any(Message.class));
    }

    @ParameterizedTest
    @EnumSource(value = PaymentState.class, names = "RECEIVED", mode = EnumSource.Mode.EXCLUDE)
    void processPayment_paymentNotInReceivedState_acksWithoutCreatingSession(PaymentState state) throws Exception {
        UUID paymentId = UUID.randomUUID();
        Payment payment = mock(Payment.class);
        when(payment.getState()).thenReturn(state);
        when(paymentService.findById(paymentId)).thenReturn(Optional.of(payment));

        processor.processPayment(messageFor(paymentId), channel);

        verify(channel).basicAck(1L, false);
        verify(stripeGateway, never()).createCheckoutSession(any());
        verify(paymentService, never()).markInProgress(any(), any(), any());
        verify(rabbitTemplate, never()).send(any(String.class), any(String.class), any(Message.class));
    }

    @Test
    void processPayment_stripeFailure_firstAttempt_republishesWithRetryCount1AndAcks() throws Exception {
        UUID paymentId = UUID.randomUUID();
        Payment payment = stubReceivedPayment(paymentId);
        when(payment.getId()).thenReturn(paymentId);
        when(stripeGateway.createCheckoutSession(payment)).thenThrow(new RuntimeException("Stripe unavailable"));

        processor.processPayment(messageFor(paymentId), channel);

        ArgumentCaptor<Message> retryMsg = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(RabbitMQConfig.EXCHANGE), eq(RabbitMQConfig.ROUTING_KEY), retryMsg.capture());
        assertThat(retryMsg.getValue().getMessageProperties().<Integer>getHeader("x-retry-count")).isEqualTo(1);
        verify(channel).basicAck(1L, false);
        verify(paymentService, never()).markFailed(any(UUID.class));
    }

    @Test
    void processPayment_stripeFailure_retriesExhausted_nacksAndMarksFailed() throws Exception {
        UUID paymentId = UUID.randomUUID();
        Payment payment = stubReceivedPayment(paymentId);
        when(payment.getId()).thenReturn(paymentId);
        when(stripeGateway.createCheckoutSession(payment)).thenThrow(new RuntimeException("Stripe unavailable"));

        processor.processPayment(messageFor(paymentId, 3), channel);

        verify(paymentService).markFailed(paymentId);
        verify(channel).basicNack(1L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(rabbitTemplate, never()).send(any(String.class), any(String.class), any(Message.class));
    }
}
