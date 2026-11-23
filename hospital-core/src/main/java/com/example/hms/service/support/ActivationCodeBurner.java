package com.example.hms.service.support;

import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * QA r70 review of #1281: an invitation code that took too many wrong tries is burned (its confirmation code and
 * any temporary password cleared) in a transaction of its own, so the burn stands although the code check that
 * triggers it then refuses and rolls back. A bean of its own: a REQUIRES_NEW method called through {@code this}
 * would run inside the caller's transaction and be rolled back with it.
 */
@Component
@RequiredArgsConstructor
public class ActivationCodeBurner {

    private final UserRoleHospitalAssignmentRepository assignmentRepository;

    /** Burns the code of a still-pending invitation; answers whether a row was burned. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean burn(UUID assignmentId) {
        return assignmentRepository.burnPendingConfirmationCode(assignmentId) > 0;
    }
}
