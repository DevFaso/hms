package com.example.hms.service.pharmacy;

import com.example.hms.repository.pharmacy.DispenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * G15 AC-13: claims one prepared fill's reminder in a transaction of its own,
 * then lets the SMS go out after that commit.
 *
 * <p>The claim is a conditional UPDATE (stamp only while the fill is still
 * PENDING and unstamped), and it must be COMMITTED before anything is sent:
 * riding in a sweep-wide transaction, a crash after the SMS would roll the
 * stamp back and the next sweep would send again. {@code REQUIRES_NEW} on a
 * separate bean (self-invocation would bypass the proxy), the same shape as
 * {@code ReminderClaimService} for appointments.
 */
@Service
@RequiredArgsConstructor
public class ReadyReminderClaimService {

    private final DispenseRepository dispenseRepository;
    private final PharmacyServiceSupport support;

    /** @return true for the one caller that won the claim and queued the SMS. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimAndNotify(UUID dispenseId, LocalDateTime now) {
        if (dispenseRepository.claimReadyReminder(dispenseId, now) != 1) {
            return false;
        }
        // Rendered inside this transaction; sent after it commits.
        dispenseRepository.findById(dispenseId).ifPresent(d ->
                support.notifyReadyReminder(d.getPatient(), d.getPharmacy(), d.getMedicationName()));
        return true;
    }
}
