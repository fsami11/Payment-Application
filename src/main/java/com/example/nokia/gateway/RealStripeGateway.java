package com.example.nokia.gateway;

import com.example.nokia.domain.Payment;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@Profile("!loadtest")
public class RealStripeGateway implements StripeGateway {

    @Value("${stripe.success-url}")
    private String successUrl;

    @Value("${stripe.cancel-url}")
    private String cancelUrl;

    @Override
    public StripeSession createCheckoutSession(Payment payment) {
        try {
            SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
                    .setSuccessUrl(successUrl + "?paymentId=" + payment.getId())
                    .setCancelUrl(cancelUrl + "?paymentId=" + payment.getId())
                    .putMetadata("payment_id", payment.getId().toString())
                    .addLineItem(SessionCreateParams.LineItem.builder()
                            .setQuantity(1L)
                            .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                    .setCurrency(payment.getCurrency().toLowerCase())
                                    .setUnitAmount(payment.getAmount()
                                            .multiply(BigDecimal.valueOf(100))
                                            .longValue())
                                    .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                            .setName("Payment")
                                            .build())
                                    .build())
                            .build())
                    .build();

            RequestOptions options = RequestOptions.builder()
                    .setIdempotencyKey(payment.getId().toString())
                    .build();

            Session session = Session.create(params, options);
            return new StripeSession(session.getId(), session.getUrl());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Stripe checkout session", e);
        }
    }
}
