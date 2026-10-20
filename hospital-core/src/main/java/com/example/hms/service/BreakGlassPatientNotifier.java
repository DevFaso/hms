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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.hms.utility.TransactionCallbacks;

import java.util.Locale;
import java.util.UUID;

/**
 * QA D410: a break-the-glass session overrides the patient's record-sharing
 * opt-out (the patient portal promises that "an emergency access stays
 * possible, recorded, and shown to you"). The declaration is already audited;
 * a patient who opted out is also told, in the portal inbox, that their
 * opt-out was overridden. Best effort: a notification failure never blocks
 * emergency access. The message is composed inside the declare transaction,
 * but the save runs only AFTER that transaction commits, in its own
 * REQUIRES_NEW transaction: a failed save can then neither mark the
 * declaration rollback-only nor lose the session and its audit row.
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
    private final PlatformTransactionManager transactionManager;

    /** @return true when the patient had opted out and a notification was scheduled */
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
            String username = portalUser.getUsername();
            UUID patientId = patient.getId();
            TransactionCallbacks.afterCommit(() -> send(message, username, patientId));
            return true;
        } catch (RuntimeException ex) {
            log.warn("Break-glass opt-out notification failed for patient {}: {}", patient.getId(), ex.getClass().getSimpleName());
            return false;
        }
    }

    private void send(String message, String username, UUID patientId) {
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.executeWithoutResult(status ->
                notificationService.createNotification(message, username, NOTIFICATION_TYPE));
        } catch (RuntimeException ex) {
            log.warn("Break-glass opt-out notification failed for patient {}: {}", patientId, ex.getClass().getSimpleName());
        }
    }
}
