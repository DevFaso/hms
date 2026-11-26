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
    /** QA D1762: the labour transfer and arrival notice (no clinical value behind it to re-measure). */
    public static final String SOURCE_TRANSFER = "LABOR_TRANSFER";
    /**
     * QA D3304 (r70): a maternal early-warning page (medium, high or a RED add-on alert) raised from a ward
     * vital set. It reached the doctors as a bell notice only, with nothing to acknowledge and no reminder,
     * while a partograph alert of the same severity on the same woman had both.
     */
    public static final String SOURCE_VITAL_SIGN = "VITAL_SIGN";
    /**
     * QA D3862 (r77, the D3304 sibling): an adult NEWS2 in the HIGH band from any vital set (ward, triage, the
     * vitals API). It reached the responsible doctor as a bell notice only, with nothing to acknowledge and no
     * reminder, while a maternal MEWS of the same severity had both. Its circle is the responsible doctor and the
     * nurse in charge, never the obstetric team ({@code ObstetricAlertEscalationWorker}).
     */
    public static final String SOURCE_NEWS2 = "NEWS2_VITAL_SIGN";
    /**
     * QA D3862 (the class): an URGENT newborn finding on a vital set recorded outside the newborn assessment (the
     * nurse station, triage, the vitals API). The same finding recorded in the assessment was an item to
     * acknowledge; on a vital set it was a bell notice only.
     */
    public static final String SOURCE_NEWBORN_VITAL_SIGN = "NEWBORN_VITAL_SIGN";

    /**
     * Every source type an escalation row may carry. The column's CHECK
     * constraint (latest: V362) must allow each of them;
     * ObstetricAlertEscalationSourceTypeGuardTest fails when the two drift.
     */
    public static final java.util.Set<String> SOURCE_TYPES = java.util.Set.of(
        SOURCE_PARTOGRAPH, SOURCE_DELIVERY, SOURCE_NEWBORN, SOURCE_POSTPARTUM, SOURCE_TRANSFER, SOURCE_VITAL_SIGN,
        SOURCE_NEWS2, SOURCE_NEWBORN_VITAL_SIGN);

    /**
     * QA D3862: the early-warning sources of a vital set. Each is filed once per patient and alert type while an
     * earlier one is still being escalated (a HIGH NEWS2 is re-measured every 15 minutes: one alert keeps reminding,
     * a new item per set would bury it), and its first notice rings only the people the raising module did not.
     */
    public static final java.util.Set<String> VITAL_SET_EARLY_WARNING_SOURCES = java.util.Set.of(
        SOURCE_NEWS2, SOURCE_NEWBORN_VITAL_SIGN);

    /**
     * One URGENT alert, as plain values: the callback runs after the commit,
     * with no persistence context, so it must never touch an entity.
     */
    public record UrgentAlert(UUID hospitalId, UUID patientId, String patientName,
                              String sourceType, UUID sourceId, String alertType, String typeLabel,
                              String alertMessage, UUID documentedByUserId, String notificationText,
                              String alertMessageKey, java.util.List<Object> alertMessageArgs,
                              java.util.Set<String> alreadyNotified) {

        /**
         * QA D665: the alert sentence as a key and its arguments, so each reader gets it in her language.
         * QA D3862: {@code alreadyNotified}, the people the raising module rang for this very reading (a vital set's
         * page): they are in the first circle and get their item to acknowledge, never a second bell.
         */
        public UrgentAlert {
            alertMessageArgs = alertMessageArgs == null ? null : java.util.List.copyOf(alertMessageArgs);
            alreadyNotified = alreadyNotified == null ? java.util.Set.of()
                : java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(alreadyNotified));
        }

        /** An alert whose raising module rang nobody before it. */
        public UrgentAlert(UUID hospitalId, UUID patientId, String patientName,
                           String sourceType, UUID sourceId, String alertType, String typeLabel,
                           String alertMessage, UUID documentedByUserId, String notificationText,
                           String alertMessageKey, java.util.List<Object> alertMessageArgs) {
            this(hospitalId, patientId, patientName, sourceType, sourceId, alertType, typeLabel, alertMessage,
                documentedByUserId, notificationText, alertMessageKey, alertMessageArgs, null);
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
        if (!SOURCE_TYPES.contains(alert.sourceType())) {
            // QA D1762: a source the table's CHECK does not know can never be saved.
            log.error("Urgent obstetric alert for {} {} not escalated: unknown source type",
                alert.sourceType(), alert.sourceId());
            return;
        }
        TransactionCallbacks.afterCommit(() -> {
            try {
                worker.raise(alert);
            } catch (RuntimeException ex) {
                // QA D1762: an urgent alert left without its escalation is a lost safety net, never a WARN.
                log.error("Urgent obstetric alert for {} {} not escalated: {}",
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
                log.error("Obstetric alert escalation failed for {}: {}", id, ex.getClass().getSimpleName());
            }
        }
        return sent;
    }
}
