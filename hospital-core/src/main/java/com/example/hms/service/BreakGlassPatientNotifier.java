package com.example.hms.service;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.repository.PatientRecordSharingOptOutRepository;
import com.example.hms.service.i18n.PatientLocaleResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * QA D410: a break-the-glass session overrides the patient's record-sharing
 * opt-out (the patient portal promises that "an emergency access stays
 * possible, recorded, and shown to you"). The declaration is already audited;
 * a patient who opted out is also told, in the portal inbox, that their
 * opt-out was overridden. Best effort: a notification failure never blocks
 * emergency access.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BreakGlassPatientNotifier {

    static final String MESSAGE_KEY = "breakGlass.patient.optOutOverridden";
    static final String NOTIFICATION_TYPE = "BREAK_GLASS_OPT_OUT_OVERRIDE";

    private final PatientRecordSharingOptOutRepository optOutRepository;
    private final NotificationService notificationService;
    private final MessageSource messageSource;
    private final PatientLocaleResolver patientLocaleResolver;

    /** @return true when the patient had opted out and was notified */
    public boolean notifyIfOptedOut(Patient patient, Hospital hospital) {
        if (patient == null || patient.getId() == null
            || !optOutRepository.existsByPatient_IdAndRevokedAtIsNull(patient.getId())) {
            return false;
        }
        User portalUser = patient.getUser();
        if (portalUser == null || portalUser.getUsername() == null) {
            return false;
        }
        try {
            Locale locale = patientLocaleResolver.resolve(patient, Locale.FRENCH);
            String hospitalName = hospital != null && hospital.getName() != null ? hospital.getName() : "";
            String message = messageSource.getMessage(MESSAGE_KEY, new Object[]{hospitalName}, locale);
            notificationService.createNotification(message, portalUser.getUsername(), NOTIFICATION_TYPE);
            return true;
        } catch (RuntimeException ex) {
            log.warn("Break-glass opt-out notification failed for patient {}: {}", patient.getId(), ex.getClass().getSimpleName());
            return false;
        }
    }
}
