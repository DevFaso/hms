package com.example.hms.repository;

import com.example.hms.model.EmailChangeRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface EmailChangeRequestRepository extends JpaRepository<EmailChangeRequest, UUID> {

    /**
     * The user's row, locked for the rest of the transaction: two parallel
     * requests must not both read the same counters or both spend the same
     * code attempt.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailChangeRequest> findByUserId(UUID userId);

    /**
     * How many OTHER accounts have had a mail sent to this address since the
     * given time: the per-address request limit, so a set of accounts cannot
     * flood one inbox.
     */
    @Query("""
        select count(r) from EmailChangeRequest r
        where r.pendingEmail = :email
          and r.userId <> :userId
          and r.codeSentAt > :since
        """)
    long countOtherRequestsForAddressSince(@Param("email") String email,
                                           @Param("userId") UUID userId,
                                           @Param("since") LocalDateTime since);
}
