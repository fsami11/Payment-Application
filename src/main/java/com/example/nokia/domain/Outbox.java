package com.example.nokia.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "outbox")
public class Outbox {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(nullable = false)
    private boolean published = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }

    public static Outbox forPayment(UUID paymentId) {
        Outbox outbox = new Outbox();
        outbox.paymentId = paymentId;
        outbox.published = false;
        return outbox;
    }

    public UUID getId() { return id; }
    public UUID getPaymentId() { return paymentId; }
    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
