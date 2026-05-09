package com.example.nokia.repository;

import com.example.nokia.domain.Outbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<Outbox, UUID> {

    @Query(value = "SELECT * FROM outbox WHERE published = false FOR UPDATE SKIP LOCKED LIMIT 10",
           nativeQuery = true)
    List<Outbox> findUnpublishedForUpdate();

    @Modifying
    @Query(value = "UPDATE outbox SET published = false WHERE payment_id = :paymentId",
           nativeQuery = true)
    void resetPublishedForPayment(@Param("paymentId") UUID paymentId);
}
