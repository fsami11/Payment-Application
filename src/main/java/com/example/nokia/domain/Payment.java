package com.example.nokia.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentState state;

    @Column(name = "stripe_session_id")
    private String stripeSessionId;

    @Column(name = "stripe_session_url")
    private String stripeSessionUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stripe_response_payload", columnDefinition = "jsonb")
    private String stripeResponsePayload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public PaymentState getState() { return state; }
    public void setState(PaymentState state) { this.state = state; }
    public String getStripeSessionId() { return stripeSessionId; }
    public void setStripeSessionId(String stripeSessionId) { this.stripeSessionId = stripeSessionId; }
    public String getStripeSessionUrl() { return stripeSessionUrl; }
    public void setStripeSessionUrl(String stripeSessionUrl) { this.stripeSessionUrl = stripeSessionUrl; }
    public String getStripeResponsePayload() { return stripeResponsePayload; }
    public void setStripeResponsePayload(String stripeResponsePayload) { this.stripeResponsePayload = stripeResponsePayload; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
