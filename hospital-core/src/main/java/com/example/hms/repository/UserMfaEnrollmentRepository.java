package com.example.hms.repository;

import com.example.hms.model.UserMfaEnrollment;
import com.example.hms.enums.MfaMethodType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserMfaEnrollmentRepository extends JpaRepository<UserMfaEnrollment, UUID> {

    List<UserMfaEnrollment> findByUserId(UUID userId);

    long countByUserIdAndEnabledTrue(UUID userId);

    long countByUserIdAndEnabledTrueAndLastVerifiedAtIsNotNull(UUID userId);

    boolean existsByUserIdAndPrimaryFactorTrue(UUID userId);

    Optional<UserMfaEnrollment> findByUserIdAndMethod(UUID userId, MfaMethodType method);
}
