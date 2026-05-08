package com.example.nokia.controller;

import com.example.nokia.service.PaymentService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/stripe")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final PaymentService paymentService;

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    public WebhookController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {

        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
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
        // Payment ID is embedded in the success_url query param
        String successUrl = session.getSuccessUrl();
        if (successUrl == null) return null;
        try {
            String query = successUrl.substring(successUrl.indexOf("paymentId=") + 10);
            return UUID.fromString(query.contains("&") ? query.substring(0, query.indexOf("&")) : query);
        } catch (Exception e) {
            log.error("Could not extract payment ID from session success URL: {}", successUrl);
            return null;
        }
    }
}
