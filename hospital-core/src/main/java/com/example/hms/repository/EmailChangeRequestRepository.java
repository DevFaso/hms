package com.example.hms.repository;

import com.example.hms.model.EmailChangeRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface EmailChangeRequestRepository extends JpaRepository<EmailChangeRequest, UUID> {

    /**
     * The user's row, locked for the rest of the transaction: two parallel
     * requests must not both read the same failure count or both spend the
     * same code attempt.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailChangeRequest> findByUserId(UUID userId);
}
