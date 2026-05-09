package com.example.nokia.controller;

import com.example.nokia.gateway.WebhookVerifier;
import com.example.nokia.service.PaymentService;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/stripe")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final PaymentService paymentService;
    private final WebhookVerifier webhookVerifier;

    public WebhookController(PaymentService paymentService, WebhookVerifier webhookVerifier) {
        this.paymentService = paymentService;
        this.webhookVerifier = webhookVerifier;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {

        Event event;
        try {
            event = webhookVerifier.constructEvent(payload, sigHeader);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid Stripe webhook signature");
            return ResponseEntity.badRequest().build();
        }

        switch (event.getType()) {
            case "checkout.session.completed" -> handleSessionCompleted(event, payload);
            case "checkout.session.expired"   -> handleSessionExpired(event, payload);
            default -> log.debug("Unhandled Stripe event type: {}", event.getType());
        }

        return ResponseEntity.ok().build();
    }

    private void handleSessionCompleted(Event event, String rawPayload) {
        Session session = (Session) event.getDataObjectDeserializer()
                .getObject()
                .orElseThrow(() -> new IllegalStateException("Could not deserialize session"));

        UUID paymentId = extractPaymentId(session);
        if (paymentId == null) return;

        paymentService.markCompleted(paymentId, rawPayload);
        log.info("Payment {} marked COMPLETED via webhook", paymentId);
    }

    private void handleSessionExpired(Event event, String rawPayload) {
        Session session = (Session) event.getDataObjectDeserializer()
                .getObject()
                .orElseThrow(() -> new IllegalStateException("Could not deserialize session"));

        UUID paymentId = extractPaymentId(session);
        if (paymentId == null) return;

        paymentService.markFailed(paymentId, rawPayload);
        log.info("Payment {} marked FAILED via webhook", paymentId);
    }

    private UUID extractPaymentId(Session session) {
        String paymentId = session.getMetadata().get("payment_id");
        if (paymentId == null) {
            log.error("payment_id missing from Stripe session metadata for session {}", session.getId());
            return null;
        }
        return UUID.fromString(paymentId);
    }
}
