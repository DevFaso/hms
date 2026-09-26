package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.EmailChangeRequest;
import com.example.hms.model.User;
import com.example.hms.repository.EmailChangeRequestRepository;
import com.example.hms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The two writes of {@link OwnEmailChangeService} that can meet a unique
 * index another request just claimed, each in its own transaction. A unique
 * violation then rolls back only that inner transaction and reaches the
 * caller as a {@code DataIntegrityViolationException}, which the caller turns
 * into its answer; the caller's own transaction, and the counters and audit
 * it already recorded, are unaffected. Done in the caller's transaction
 * instead, the failed flush would mark it rollback-only and the request would
 * end as a 500.
 */
@Component
@RequiredArgsConstructor
public class EmailChangeWrites {

    private final EmailChangeRequestRepository requestRepository;
    private final UserRepository userRepository;

    /**
     * Insert the user's row. Two first requests racing both try; the loser
     * gets the {@code uq_email_change_user} violation, and the caller then
     * reads the winner's row.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createRow(UUID userId) {
        requestRepository.saveAndFlush(EmailChangeRequest.builder().userId(userId).build());
    }

    /**
     * Apply the confirmed address. Flushed here, so a unique violation (another
     * account took the address after the last check) surfaces now, to the caller.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyEmail(UUID userId, String email) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found with ID: " + userId));
        user.setEmail(email);
        userRepository.saveAndFlush(user);
    }
}
