package com.example.nokia.gateway;

import com.stripe.model.Event;

public interface WebhookVerifier {
    Event constructEvent(String payload, String sigHeader);
}
