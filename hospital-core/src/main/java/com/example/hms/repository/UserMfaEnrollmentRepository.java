package com.example.hms.repository;

import com.example.hms.model.UserMfaEnrollment;
import com.example.hms.enums.MfaMethodType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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

    /**
     * The row, locked for the rest of the transaction: a step-up check and the
     * change it guards (MfaService) read and write the same row, so a
     * concurrent replacement cannot slip in between them.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from UserMfaEnrollment e where e.user.id = :userId and e.method = :method")
    Optional<UserMfaEnrollment> lockByUserIdAndMethod(@Param("userId") UUID userId,
                                                      @Param("method") MfaMethodType method);

    /**
     * Stamps one column, so a sign-in never writes back a secret it read
     * before a replacement was confirmed (a full-entity save would).
     */
    @Modifying
    @Query("update UserMfaEnrollment e set e.lastVerifiedAt = :at where e.user.id = :userId and e.method = :method")
    int touchLastVerifiedAt(@Param("userId") UUID userId, @Param("method") MfaMethodType method,
                            @Param("at") LocalDateTime at);
}
