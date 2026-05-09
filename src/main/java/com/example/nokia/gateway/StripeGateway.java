package com.example.nokia.gateway;

import com.example.nokia.domain.Payment;

public interface StripeGateway {
    StripeSession createCheckoutSession(Payment payment);
}
