package com.example.nokia.gateway;

import com.example.nokia.domain.Payment;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Profile("loadtest")
public class FakeStripeGateway implements StripeGateway {

    @Override
    public StripeSession createCheckoutSession(Payment payment) {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String fakeId = "cs_fake_" + UUID.randomUUID();
        return new StripeSession(fakeId, "http://localhost/fake-checkout/" + fakeId);
    }
}
