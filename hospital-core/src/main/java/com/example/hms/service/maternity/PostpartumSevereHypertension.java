package com.example.hms.service.maternity;

import com.example.hms.repository.DeliveryRecordRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * QA D1890: severe hypertension after birth. The one place that holds the rule; the postpartum
 * observation, every vitals write (nurse station, triage, the vitals API) and the doctor's BPA panel
 * all ask it, so the three surfaces cannot drift apart.
 *
 * <p>The rule: a blood pressure with systolic &ge; {@value #SEVERE_SYSTOLIC} mmHg OR diastolic &ge;
 * {@value #SEVERE_DIASTOLIC} mmHg (inclusive, either one suffices), taken after a birth, raises an
 * URGENT "obstetric review required" warning. It never refuses a reading: the value is saved first
 * and acknowledging the warning is never required to save.
 *
 * <p>"After birth" is read from the record, not assumed: a delivery recorded for this mother (at any
 * hospital) whose birth time is at or before the reading and no more than
 * {@value #AFTER_BIRTH_LOOKBACK_DAYS} days before it. That is the window in which a recorded birth
 * already opens postpartum care (QA D517); no other postpartum period is invented here.
 *
 * <p>PROVISIONAL (user decision of 2026-11-14, source: NICE guidance on postnatal hypertension):
 * pending the obstetric lead's approval. See docs/qa/approvals-register.md and
 * docs/qa/regles-cliniques-provisoires.md before changing a number.
 */
@Component
public class PostpartumSevereHypertension {

    /** Severe range, systolic (mmHg, inclusive). */
    public static final int SEVERE_SYSTOLIC = 160;
    /** Severe range, diastolic (mmHg, inclusive). */
    public static final int SEVERE_DIASTOLIC = 110;
    /** How far back a recorded birth makes a reading "after birth" (QA D517's postpartum window). */
    public static final int AFTER_BIRTH_LOOKBACK_DAYS = 365;
    /** The alert code on every surface. */
    public static final String ALERT_CODE = "postpartum-severe-hypertension";
    /** The warning sentence, the same key everywhere (rendered per reader). */
    public static final String MESSAGE_KEY = "postpartum.alert.msg.postpartum-severe-hypertension";

    /** One blood-pressure reading: when it was taken, and when it reached the chart. */
    public record Reading(Integer systolic, Integer diastolic, LocalDateTime takenAt, LocalDateTime enteredAt) {
    }

    private final DeliveryRecordRepository deliveryRecordRepository;

    public PostpartumSevereHypertension(DeliveryRecordRepository deliveryRecordRepository) {
        this.deliveryRecordRepository = deliveryRecordRepository;
    }

    /** The thresholds alone: systolic &ge; 160 OR diastolic &ge; 110 mmHg. A missing value never triggers. */
    public static boolean isSevere(Integer systolic, Integer diastolic) {
        return (systolic != null && systolic >= SEVERE_SYSTOLIC)
            || (diastolic != null && diastolic >= SEVERE_DIASTOLIC);
    }

    /** A birth is recorded for this mother at or before {@code at}, within the postpartum window. */
    public boolean isAfterBirth(UUID patientId, LocalDateTime at) {
        if (patientId == null || at == null || deliveryRecordRepository == null) {
            return false;
        }
        return deliveryRecordRepository.existsByPatient_IdAndBirthDateTimeBetween(
            patientId, at.minusDays(AFTER_BIRTH_LOOKBACK_DAYS), at);
    }

    /** The whole rule: a severe reading taken after a recorded birth. Queries only for a severe reading. */
    public boolean applies(UUID patientId, Integer systolic, Integer diastolic, LocalDateTime takenAt) {
        return isSevere(systolic, diastolic) && isAfterBirth(patientId, takenAt);
    }
}
