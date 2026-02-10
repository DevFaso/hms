package com.example.hms.repository;

import com.example.hms.model.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    void deleteByUser_IdAndConsumedAtIsNull(UUID userId);

    // Optional housekeeping
    long deleteByExpirationBefore(LocalDateTime threshold);
}
