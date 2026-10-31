package com.example.hms.service.lab;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.NotificationPreference;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.repository.NotificationPreferenceRepository;
import com.example.hms.service.CriticalValueNotificationService;
import com.example.hms.service.NotificationService;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.service.i18n.NotificationLocalizer;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * What a released lab result tells people besides the critical page.
 *
 * <p><b>QA D938</b>: {@link NotificationType#LAB_RESULT} existed, the patient
 * portal offered a "lab result" preference for it, and nothing ever wrote one,
 * so a patient was never told a result was ready, not even a critical one.
 * Every release path now calls {@link #patientResultReleased}: the patient's
 * portal account gets an in-app notification naming the TEST only, never the
 * value (a value read without its context, and before the clinician has seen
 * it, is the clinician's to explain). The patient's own preference for
 * LAB_RESULT / IN_APP is honoured the way the appointment reminder honours
 * its own: an explicit disabled row turns it off, no row means on.
 *
 * <p><b>QA D931</b>: a released correction ("Positif" becomes "Négatif") reached
 * the ordering clinician as an ordinary result with nothing saying it replaced
 * one they may already have acted on. {@link #correctionReleased} tells them,
 * naming the old and the new value.
 *
 * <p>The transaction rules are {@link LabOrderRoutingNotifier}'s, for the same
 * reasons: only plain values are read from the entities, inside the release
 * transaction; the lookup and the notification row are written after the
 * commit, each in a {@code REQUIRES_NEW} transaction of its own, and a failure
 * there is logged (ids only) and never undoes the release.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LabResultReleaseNotifier {

    static final String NOTIFICATION_TYPE = "LAB_RESULT";
    static final String PATIENT_MESSAGE_KEY = "lab.result.patient.available";
    /** QA D1016: a released correction is announced as a correction, not as a second new result. */
    static final String PATIENT_CORRECTION_MESSAGE_KEY = "lab.result.patient.corrected";
    static final String CORRECTION_MESSAGE_KEY = "lab.result.correction.notification";

    private final NotificationService notificationService;
    private final NotificationPreferenceRepository preferenceRepository;
    private final PlatformTransactionManager transactionManager;

    /**
     * Tell the patient a result is ready. Call it inside the transaction that
     * released the result; a result that is not released, or a patient with no
     * portal account, is a no-op.
     */
    public void patientResultReleased(LabResult result) {
        if (result == null || !result.isReleased()) {
            return;
        }
        LabOrder order = result.getLabOrder();
        Patient patient = order != null ? order.getPatient() : null;
        User portalUser = patient != null ? patient.getUser() : null;
        if (portalUser == null || portalUser.getId() == null
                || portalUser.getUsername() == null || portalUser.getUsername().isBlank()) {
            return;
        }
        UUID resultId = result.getId();
        UUID userId = portalUser.getId();
        String username = portalUser.getUsername();
        Object testName = testNameOf(order);
        String messageKey = LabResultCorrections.isCorrection(result) ? PATIENT_CORRECTION_MESSAGE_KEY : PATIENT_MESSAGE_KEY;
        TransactionCallbacks.afterCommit(() -> deliverToPatient(resultId, userId, username, testName, messageKey));
    }

    /**
     * Tell the ordering clinician that {@code correction}, now released,
     * replaces {@code superseded}. Call it inside the release transaction.
     */
    public void correctionReleased(LabResult correction, LabResult superseded) {
        if (correction == null || superseded == null) {
            return;
        }
        LabOrder order = correction.getLabOrder();
        User clinician = order != null && order.getOrderingStaff() != null ? order.getOrderingStaff().getUser() : null;
        if (clinician == null || clinician.getUsername() == null || clinician.getUsername().isBlank()) {
            return;
        }
        UUID correctionId = correction.getId();
        String username = clinician.getUsername();
        Object[] args = {
            testNameOf(order),
            patientNameOf(order),
            valueOf(superseded),
            valueOf(correction)
        };
        TransactionCallbacks.afterCommit(() -> {
            try {
                ownTransaction().executeWithoutResult(status ->
                    notificationService.createNotification(CORRECTION_MESSAGE_KEY, args, username, NOTIFICATION_TYPE,
                        correctionId));
            } catch (RuntimeException ex) {
                log.warn("Correction notification not written for lab result {}: {}", correctionId,
                    ex.getClass().getSimpleName());
            }
        });
    }

    /** The value as the clinician reads it: the staff language's number format, then the unit. */
    public static String valueOf(LabResult result) {
        // QA D1095: the definition's unit when none was typed.
        return CriticalValueNotificationService.withUnit(
            CriticalValueNotificationService.localizedValue(result.getResultValue(), NotificationLocales.STAFF), result);
    }

    private void deliverToPatient(UUID resultId, UUID userId, String username, Object testName, String messageKey) {
        try {
            ownTransaction().executeWithoutResult(status -> {
                if (!inAppEnabled(userId)) {
                    return;
                }
                notificationService.createNotification(messageKey, new Object[] {testName}, username,
                    NOTIFICATION_TYPE, resultId);
            });
        } catch (RuntimeException ex) {
            log.warn("Patient notification not written for released lab result {}: {}", resultId,
                ex.getClass().getSimpleName());
        }
    }

    /** Explicit disabled row turns the channel off; no row means enabled (AppointmentReminderService). */
    private boolean inAppEnabled(UUID userId) {
        List<NotificationPreference> preferences =
            preferenceRepository.findByUser_IdAndNotificationType(userId, NotificationType.LAB_RESULT);
        return preferences.stream()
            .filter(preference -> preference.getChannel() == NotificationChannel.IN_APP)
            .map(NotificationPreference::isEnabled)
            .findFirst()
            .orElse(true);
    }

    private TransactionTemplate ownTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /** QA D665: keyed so the reader's language is used; QA D648: a deleted definition names a deleted test. */
    private static Object testNameOf(LabOrder order) {
        if (order == null) {
            return NotificationLocalizer.key("lab.test.fallback");
        }
        if (order.getLabTestDefinition() != null && order.getLabTestDefinition().getName() != null) {
            return order.getLabTestDefinition().getName();
        }
        return NotificationLocalizer.key(com.example.hms.mapper.LabOrderMapper.DELETED_TEST_LABEL);
    }

    private static Object patientNameOf(LabOrder order) {
        return order != null && order.getPatient() != null
            ? order.getPatient().getFullName()
            : NotificationLocalizer.key("patient.fallback.generic");
    }
}
