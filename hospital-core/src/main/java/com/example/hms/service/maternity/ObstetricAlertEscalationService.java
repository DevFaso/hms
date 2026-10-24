package com.example.hms.service.maternity;

import com.example.hms.repository.ObstetricAlertEscalationRepository;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * QA D605: URGENT obstetric and neonatal alerts (partograph, delivery, newborn
 * assessment, postpartum observation) reached only the clinician who typed
 * them; no doctor or on-call team was told and nothing escalated.
 *
 * <p>Template: the critical-lab and PRO screening escalation chains. Raising
 * an alert waits for the clinical record to commit (no notice for a record
 * that rolled back) and runs in its own transaction (a failed notice never
 * undoes the record). The sweep re-notifies a widening circle every
 * {@code escalate-after-minutes} until somebody other than the documenter
 * acknowledges the in-basket item, up to {@code max-rounds} reminders, at
 * most {@code batch-size} alerts per pass.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ObstetricAlertEscalationService {

    public static final String SOURCE_PARTOGRAPH = "PARTOGRAPH_ENTRY";
    public static final String SOURCE_DELIVERY = "DELIVERY";
    public static final String SOURCE_NEWBORN = "NEWBORN_ASSESSMENT";
    public static final String SOURCE_POSTPARTUM = "POSTPARTUM_OBSERVATION";

    /**
     * One URGENT alert, as plain values: the callback runs after the commit,
     * with no persistence context, so it must never touch an entity.
     */
    public record UrgentAlert(UUID hospitalId, UUID patientId, String patientName,
                              String sourceType, UUID sourceId, String alertType, String typeLabel,
                              String alertMessage, UUID documentedByUserId, String notificationText,
                              String alertMessageKey, java.util.List<Object> alertMessageArgs) {

        /** QA D665: the alert sentence as a key and its arguments, so each reader gets it in her language. */
        public UrgentAlert {
            alertMessageArgs = alertMessageArgs == null ? null : java.util.List.copyOf(alertMessageArgs);
        }

        /** An alert with no message key: its notices carry the stored staff-locale sentence. */
        public UrgentAlert(UUID hospitalId, UUID patientId, String patientName,
                           String sourceType, UUID sourceId, String alertType, String typeLabel,
                           String alertMessage, UUID documentedByUserId, String notificationText) {
            this(hospitalId, patientId, patientName, sourceType, sourceId, alertType, typeLabel,
                alertMessage, documentedByUserId, notificationText, null, null);
        }
    }

    private final ObstetricAlertEscalationRepository escalationRepository;
    private final ObstetricAlertEscalationWorker worker;
    private final Clock clock;

    @Value("${hms.maternity.alert-escalation.escalate-after-minutes:15}")
    private long escalateAfterMinutes = 15;

    @Value("${hms.maternity.alert-escalation.max-rounds:3}")
    private short maxRounds = 3;

    @Value("${hms.maternity.alert-escalation.batch-size:100}")
    private int batchSize = 100;

    /** Notify the team once the record that raised the alert has committed. Never throws. */
    public void raiseAfterCommit(UrgentAlert alert) {
        if (alert == null || alert.hospitalId() == null || alert.patientId() == null || alert.sourceId() == null) {
            return;
        }
        TransactionCallbacks.afterCommit(() -> {
            try {
                worker.raise(alert);
            } catch (RuntimeException ex) {
                log.warn("Urgent obstetric alert for {} {} not escalated: {}",
                    alert.sourceType(), alert.sourceId(), ex.getClass().getSimpleName());
            }
        });
    }

    /**
     * Re-escalate the alerts still unacknowledged past the delay. Per-alert
     * failures are logged and skipped so one bad row never stalls the sweep.
     *
     * @return reminders sent on this pass
     */
    public Integer escalateOverdue() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(Duration.ofMinutes(escalateAfterMinutes));
        List<UUID> ids = escalationRepository.findIdsAwaitingEscalation(cutoff, maxRounds,
            PageRequest.of(0, Math.max(1, batchSize)));
        int sent = 0;
        for (UUID id : ids) {
            try {
                if (worker.escalateOne(id)) {
                    sent++;
                }
            } catch (RuntimeException ex) {
                log.warn("Obstetric alert escalation failed for {}: {}", id, ex.getClass().getSimpleName());
            }
        }
        return sent;
    }
}
