package com.example.nokia.gateway;

import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("loadtest")
public class FakeWebhookVerifier implements WebhookVerifier {

    @Override
    public Event constructEvent(String payload, String sigHeader) {
        return ApiResource.GSON.fromJson(payload, Event.class);
    }
}
