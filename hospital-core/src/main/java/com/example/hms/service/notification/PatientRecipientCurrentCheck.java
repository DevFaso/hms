package com.example.hms.service.notification;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** After-commit sends must not reuse the just-committed persistence context's cached patient. */
@Service @RequiredArgsConstructor
public class PatientRecipientCurrentCheck {
    private final PatientRepository patients;
    private final PatientRecipientVerification verification;
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public boolean allows(UUID patientId, NotificationChannel channel, String recipient) {
        return patients.findByIdUnscoped(patientId).map(patient -> verification.allows(patient, channel, recipient)).orElse(false);
    }
}
