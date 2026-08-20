package com.example.hms.repository;

import com.example.hms.enums.PhoneOtpPurpose;
import com.example.hms.model.PhoneOtpChallenge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PhoneOtpChallengeRepository extends JpaRepository<PhoneOtpChallenge, UUID> {

    List<PhoneOtpChallenge> findByPhoneNumberAndPurposeAndConsumedFalse(String phoneNumber, PhoneOtpPurpose purpose);

    Optional<PhoneOtpChallenge> findByIdAndRequestedByUserId(UUID id, UUID requestedByUserId);

    /** Send-abuse guard: how many codes this staff member dispatched recently. */
    long countByRequestedByUserIdAndCreatedAtAfter(UUID requestedByUserId, java.time.LocalDateTime after);
}
