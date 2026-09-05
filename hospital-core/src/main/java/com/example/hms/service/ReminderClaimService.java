package com.example.hms.service;

import com.example.hms.repository.AppointmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Claims an appointment for reminding in a transaction of its own.
 *
 * <p>The claim is a conditional UPDATE (stamp only if unstamped) and it must
 * be COMMITTED before anything is sent: if it rode inside the sweep's
 * transaction, a crash after the SMS went out would roll the stamp back and
 * the next sweep would send again — the exact duplicate the claim exists to
 * prevent. {@code REQUIRES_NEW} on a separate bean (self-invocation would
 * bypass the proxy) is what makes the stamp durable on its own.
 */
@Service
@RequiredArgsConstructor
public class ReminderClaimService {

    private final AppointmentRepository appointmentRepository;

    /** @return true for the one caller that won the claim; false for everyone else. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(UUID appointmentId, LocalDateTime now) {
        return appointmentRepository.claimReminder(appointmentId, now) == 1;
    }
}
