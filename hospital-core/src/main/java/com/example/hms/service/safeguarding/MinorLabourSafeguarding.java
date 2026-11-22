package com.example.hms.service.safeguarding;

import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.model.Patient;
import com.example.hms.model.safeguarding.SafeguardingReview;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * r53, provisional rule (user decision 2026-11-14, pending the obstetric lead's and the child-protection lead's
 * approval; WHO guidance supplied by the user; approvals register): a 10-year-old was admitted in labour by a
 * midwife with no warning. The obstetric age floor ({@code ObstetricEligibility.MIN_OBSTETRIC_AGE_YEARS = 8}) is a
 * software limit, not a clinical approval.
 *
 * <ul>
 *   <li>The admission is never blocked, delayed or made to wait for a second signature. Nothing here throws into the
 *       caller: the review is opened after the admission commits, in its own transaction.</li>
 *   <li>A labour admission of a patient younger than {@link #ageUnderYears} full years at admission (provisional
 *       default 13, {@code hms.safeguarding.minor-labour.age-under-years}) opens one safeguarding review per
 *       patient and hospital, and files an URGENT safeguarding task and a senior obstetric review request for that
 *       hospital's clinicians ({@link SafeguardingReviewWorker}).</li>
 *   <li>No relative is notified, nothing leaves the hospital, no legal report or external escalation is made.</li>
 * </ul>
 * Every path that opens a labour admission calls {@link #onLabourAdmission} ({@code MinorLabourSafeguardingPathGuardTest}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ProvisionalRule(ClinicalRuleSetKey.MINOR_LABOUR_SAFEGUARDING)
public class MinorLabourSafeguarding {

    /** Provisional default, pending local approval: under 13 at admission. */
    public static final int PROVISIONAL_DEFAULT_AGE_UNDER_YEARS = 13;

    private final SafeguardingReviewWorker worker;

    @Value("${hms.safeguarding.minor-labour.age-under-years:" + PROVISIONAL_DEFAULT_AGE_UNDER_YEARS + "}")
    private int ageUnderYears = PROVISIONAL_DEFAULT_AGE_UNDER_YEARS;

    /** One labour admission to open a review for, as plain values: the callback runs with no persistence context. */
    public record LabourAdmission(UUID hospitalId, UUID patientId, String sourceType, UUID sourceId, int ageYears,
                                  int thresholdYears, LocalDateTime admittedAt, UUID raisedByUserId) {
    }

    /**
     * A labour admission (an admission of type LABOR_DELIVERY, or a labour episode started or continued at a
     * hospital) was just written. Opens the review after the commit when she is under the threshold. Never throws.
     */
    public void onLabourAdmission(Patient patient, UUID hospitalId, LocalDateTime admittedAt, String sourceType,
                                  UUID sourceId, UUID actorUserId) {
        try {
            if (patient == null || patient.getId() == null || hospitalId == null || sourceId == null) {
                return;
            }
            LocalDateTime at = admittedAt != null ? admittedAt : LocalDateTime.now();
            OptionalInt age = ageAt(patient.getDateOfBirth(), at.toLocalDate());
            if (age.isEmpty() || !isCovered(age.getAsInt())) {
                return;
            }
            LabourAdmission admission = new LabourAdmission(hospitalId, patient.getId(), sourceType, sourceId,
                age.getAsInt(), ageUnderYears, at, actorUserId);
            TransactionCallbacks.afterCommit(() -> {
                try {
                    worker.open(admission);
                } catch (RuntimeException ex) {
                    // A safeguarding review left unopened is a lost safety net, never a WARN.
                    log.error("[SAFEGUARDING] Review for {} {} at hospital {} not opened: {}", sourceType, sourceId,
                        hospitalId, ex.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException ex) {
            log.error("[SAFEGUARDING] Labour admission {} {} not screened: {}", sourceType, sourceId,
                ex.getClass().getSimpleName());
        }
    }

    /** Whether a patient of this age at admission is in the covered band. */
    public boolean isCovered(int ageYears) {
        return ageYears >= 0 && ageYears < ageUnderYears;
    }

    public int ageUnderYears() {
        return ageUnderYears;
    }

    /** Full years at {@code on}; empty with no birth date, or one after the admission. */
    static OptionalInt ageAt(LocalDate dateOfBirth, LocalDate on) {
        if (dateOfBirth == null || on == null || dateOfBirth.isAfter(on)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(Period.between(dateOfBirth, on).getYears());
    }

    /** The source types a review records (the V296 CHECK). */
    public static boolean knownSource(String sourceType) {
        return SafeguardingReview.SOURCE_ADMISSION.equals(sourceType)
            || SafeguardingReview.SOURCE_LABOR_EPISODE.equals(sourceType);
    }
}
