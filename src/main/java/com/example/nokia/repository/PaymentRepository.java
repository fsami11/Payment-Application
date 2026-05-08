package com.example.nokia.repository;

import com.example.nokia.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    @Query(value = """
            SELECT * FROM payments
            WHERE state = 'IN_PROGRESS'
              AND stripe_session_url IS NULL
              AND updated_at < :threshold
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Payment> findStuckInProgress(@Param("threshold") OffsetDateTime threshold);

    @Modifying
    @Transactional
    @Query(value = "UPDATE payments SET updated_at = :updatedAt WHERE id = :id", nativeQuery = true)
    void updateUpdatedAt(@Param("id") UUID id, @Param("updatedAt") OffsetDateTime updatedAt);
}
