package com.example.nokia.dto;

import com.example.nokia.domain.Payment;
import com.example.nokia.domain.PaymentState;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        BigDecimal amount,
        String currency,
        PaymentState state,
        String sessionUrl
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getState(),
                payment.getStripeSessionUrl()
        );
    }
}
