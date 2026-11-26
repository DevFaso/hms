package com.example.hms.service.maternity;

import com.example.hms.repository.ObstetricAlertEscalationRepository;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * <p>Scorecard K4 (V386): this is the path of EVERY urgent clinical alert that is not a laboratory value or a
 * report with its own acknowledgement, whatever its source ({@link #SOURCE_TYPES}), on the one ladder of
 * {@link com.example.hms.service.escalation.UrgentEscalationSupport}.
 *
 * <p>Raising an alert writes its row with the clinical record, in the caller's transaction: the row is the
 * obligation ({@code firstNoticeFiledAt} is null). The first notice is delivered once that transaction has
 * committed (no notice for a record that rolled back), in a transaction of its own (a failed notice never undoes
 * the record); a delivery that fails leaves the row owed, and the sweep delivers it. The sweep then takes the
 * steps of the ladder, with no cap, until somebody other than the documenter acknowledges the in-basket item or
 * the record no longer supports the alert.
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
     * Scorecard K4: a severe transfusion reaction. The clinician who asked for the blood and the covering
     * physician acknowledge it; the laboratory director gets the haemovigilance task with the first notice.
     */
    public static final String SOURCE_TRANSFUSION_REACTION = "TRANSFUSION_REACTION";
    /** Scorecard K4: a finalized culture with growth, for the clinician who ordered it. */
    public static final String SOURCE_POSITIVE_CULTURE = "POSITIVE_CULTURE";
    /** Scorecard K4: an adult's severe hypertension at the URGENT level on a vital set. */
    public static final String SOURCE_URGENT_HYPERTENSION = "URGENT_HYPERTENSION";
    /** Scorecard K4: the countersignature of an emergency O positive release, past its deadline, until it is signed. */
    public static final String SOURCE_O_POSITIVE_COUNTERSIGN = "O_POSITIVE_COUNTERSIGN";
    /** Scorecard K4: the countersignature of an incompatible release, past its deadline, until it is signed. */
    public static final String SOURCE_INCOMPATIBLE_COUNTERSIGN = "INCOMPATIBLE_COUNTERSIGN";

    /**
     * Every source type an escalation row may carry. The column's CHECK
     * constraint (latest: V362) must allow each of them;
     * ObstetricAlertEscalationSourceTypeGuardTest fails when the two drift.
     */
    public static final java.util.Set<String> SOURCE_TYPES = java.util.Set.of(
        SOURCE_PARTOGRAPH, SOURCE_DELIVERY, SOURCE_NEWBORN, SOURCE_POSTPARTUM, SOURCE_TRANSFER, SOURCE_VITAL_SIGN,
        SOURCE_NEWS2, SOURCE_NEWBORN_VITAL_SIGN, SOURCE_TRANSFUSION_REACTION, SOURCE_POSITIVE_CULTURE,
        SOURCE_URGENT_HYPERTENSION, SOURCE_O_POSITIVE_COUNTERSIGN, SOURCE_INCOMPATIBLE_COUNTERSIGN);

    /**
     * The sources that are not obstetric or neonatal: their circle is the people the raising module names and the
     * covering clinician, never the obstetric team, and their notices are never headed as an obstetric alert.
     */
    public static final java.util.Set<String> GENERAL_SOURCES = java.util.Set.of(
        SOURCE_TRANSFUSION_REACTION, SOURCE_POSITIVE_CULTURE, SOURCE_URGENT_HYPERTENSION,
        SOURCE_O_POSITIVE_COUNTERSIGN, SOURCE_INCOMPATIBLE_COUNTERSIGN);

    /**
     * The sources only their own record closes (the countersignature, once signed): an in-basket acknowledgement
     * says the reader saw the alert, and the ladder keeps escalating until the signature is on record.
     */
    public static final java.util.Set<String> CLOSED_BY_RECORD_ONLY = java.util.Set.of(
        SOURCE_O_POSITIVE_COUNTERSIGN, SOURCE_INCOMPATIBLE_COUNTERSIGN);

    /** The sources filed once per record while an alert of that record is still open. */
    public static final java.util.Set<String> ONE_OPEN_PER_RECORD = java.util.Set.of(
        SOURCE_TRANSFUSION_REACTION, SOURCE_POSITIVE_CULTURE, SOURCE_O_POSITIVE_COUNTERSIGN,
        SOURCE_INCOMPATIBLE_COUNTERSIGN);

    /**
     * QA D3862: the early-warning sources of a vital set. Each is filed once per patient and alert type while an
     * earlier one is still being escalated (a HIGH NEWS2 is re-measured every 15 minutes: one alert keeps reminding,
     * a new item per set would bury it), and its first notice rings only the people the raising module did not.
     */
    public static final java.util.Set<String> VITAL_SET_EARLY_WARNING_SOURCES = java.util.Set.of(
        SOURCE_NEWS2, SOURCE_NEWBORN_VITAL_SIGN, SOURCE_URGENT_HYPERTENSION);

    /**
     * One URGENT alert, as plain values: the callback runs after the commit,
     * with no persistence context, so it must never touch an entity.
     */
    public record UrgentAlert(UUID hospitalId, UUID patientId, String patientName,
                              String sourceType, UUID sourceId, String alertType, String typeLabel,
                              String alertMessage, UUID documentedByUserId, String notificationText,
                              String alertMessageKey, java.util.List<Object> alertMessageArgs,
                              java.util.Set<String> alreadyNotified, java.util.Set<String> namedRecipients) {

        /**
         * QA D665: the alert sentence as a key and its arguments, so each reader gets it in her language.
         * QA D3862: {@code alreadyNotified}, the people the raising module rang for this very reading (a vital set's
         * page): they are in the first circle and get their item to acknowledge, never a second bell.
         */
        public UrgentAlert {
            alertMessageArgs = alertMessageArgs == null ? null : java.util.List.copyOf(alertMessageArgs);
            alreadyNotified = alreadyNotified == null ? java.util.Set.of()
                : java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(alreadyNotified));
            namedRecipients = namedRecipients == null ? java.util.Set.of()
                : java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(namedRecipients));
        }

        /** An alert whose raising module names nobody for the first circle: the source's own circle takes it. */
        public UrgentAlert(UUID hospitalId, UUID patientId, String patientName,
                           String sourceType, UUID sourceId, String alertType, String typeLabel,
                           String alertMessage, UUID documentedByUserId, String notificationText,
                           String alertMessageKey, java.util.List<Object> alertMessageArgs,
                           java.util.Set<String> alreadyNotified) {
            this(hospitalId, patientId, patientName, sourceType, sourceId, alertType, typeLabel, alertMessage,
                documentedByUserId, notificationText, alertMessageKey, alertMessageArgs, alreadyNotified, null);
        }

        /**
         * Scorecard K4: the same alert, naming the people the first notice must reach whoever is on call (the
         * clinician who asked for the blood, the clinician who ordered the culture, the laboratory directors).
         */
        public UrgentAlert withAlreadyNotified(java.util.Collection<String> rang) {
            return new UrgentAlert(hospitalId, patientId, patientName, sourceType, sourceId, alertType, typeLabel,
                alertMessage, documentedByUserId, notificationText, alertMessageKey, alertMessageArgs,
                rang == null ? null : new java.util.LinkedHashSet<>(rang), namedRecipients);
        }

        public UrgentAlert withNamedRecipients(java.util.Collection<String> named) {
            return new UrgentAlert(hospitalId, patientId, patientName, sourceType, sourceId, alertType, typeLabel,
                alertMessage, documentedByUserId, notificationText, alertMessageKey, alertMessageArgs,
                alreadyNotified, named == null ? null : new java.util.LinkedHashSet<>(named));
        }

        /** An alert whose raising module rang nobody before it. */
        public UrgentAlert(UUID hospitalId, UUID patientId, String patientName,
                           String sourceType, UUID sourceId, String alertType, String typeLabel,
                           String alertMessage, UUID documentedByUserId, String notificationText,
                           String alertMessageKey, java.util.List<Object> alertMessageArgs) {
            this(hospitalId, patientId, patientName, sourceType, sourceId, alertType, typeLabel, alertMessage,
                documentedByUserId, notificationText, alertMessageKey, alertMessageArgs, null, null);
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

    /** A first notice is the after-commit delivery's for this long; a row still owed after it is the sweep's. */
    static final Duration OWED_AFTER = Duration.ofMinutes(1);

    /**
     * Files the alert with the record that raised it and notifies its circle once that record has committed.
     *
     * <p>The row is written here, in the caller's transaction: it commits with the clinical record or not at all,
     * and from then on the first notice is owed. Its delivery follows the commit and never throws; a delivery that
     * fails leaves the row owed and the sweep delivers it ({@link #escalateOverdue}).
     */
    public void raiseAfterCommit(UrgentAlert alert) {
        file(alert, false);
    }

    /**
     * Scorecard K4: files an alert whose first notice its raising module has just delivered itself, in this same
     * transaction (the overdue countersignature's task to the laboratory director). The ladder takes it from its
     * first step: nobody is told twice now, and nobody stops being told later.
     */
    public void raiseAlreadyNotified(UrgentAlert alert) {
        file(alert, true);
    }

    private void file(UrgentAlert alert, boolean firstNoticeDelivered) {
        if (alert == null || alert.hospitalId() == null || alert.patientId() == null || alert.sourceId() == null) {
            return;
        }
        if (!SOURCE_TYPES.contains(alert.sourceType())) {
            // QA D1762: a source the table's CHECK does not know can never be saved.
            log.error("Urgent alert for {} {} not escalated: unknown source type",
                alert.sourceType(), alert.sourceId());
            return;
        }
        // Not caught: the row joins the caller's transaction, and a failure to write it has already doomed that
        // transaction. A clinical write that is retried is recoverable; an urgent alert nobody is owed is not.
        UUID rowId = worker.file(alert, firstNoticeDelivered);
        if (rowId == null || firstNoticeDelivered) {
            return;
        }
        TransactionCallbacks.afterCommit(() -> {
            try {
                worker.deliverFirstNotice(rowId, alert);
            } catch (RuntimeException ex) {
                // The row stays owed: the sweep delivers it. Loud all the same (QA D1762).
                log.error("Urgent alert for {} {} not delivered, left to the sweep: {}",
                    alert.sourceType(), alert.sourceId(), ex.getClass().getSimpleName());
            }
        });
    }

    /**
     * One pass over every alert nobody has acknowledged: an owed first notice is delivered, an acknowledgement is
     * recorded, and the step of the ladder that is due is taken. There is no cap on the steps. Per-alert failures
     * are logged and skipped so one bad row never stalls the sweep.
     *
     * @return notices and steps sent on this pass
     */
    public Integer escalateOverdue() {
        List<UUID> ids = escalationRepository.findOpenIds(LocalDateTime.now(clock).minus(OWED_AFTER));
        int sent = 0;
        for (UUID id : ids) {
            try {
                if (worker.escalateOne(id)) {
                    sent++;
                }
            } catch (RuntimeException ex) {
                log.error("Urgent alert escalation failed for {}: {}", id, ex.getClass().getSimpleName());
            }
        }
        return sent;
    }
}
