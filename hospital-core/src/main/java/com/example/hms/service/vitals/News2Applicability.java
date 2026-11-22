package com.example.hms.service.vitals;

import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.model.Patient;
import com.example.hms.utility.NewsScoreCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Review of #1237 (r65, High): whether a NEWS2 score may be shown for a patient, the ONE place
 * that says so. NEWS2 is not validated in pregnancy, after a birth or for a newborn (RCP 2017).
 * Since D2580 the labour, postpartum and newborn readings reach the chart, and a postpartum
 * 162/104 read « NEWS2 0 Risque faible »: a falsely reassuring score beside a CRITICAL maternal
 * alert.
 *
 * <p>Conservative and provisional: for a pregnant or postpartum woman (the shared
 * {@link SevereHypertension#scopeOf} rule, PREGNANT or POSTPARTUM) and for a newborn, no score is
 * SHOWN; the chart and the advisory say « NEWS2 non validé » and point to the maternity alerts.
 * A score never lengthens the vitals round.
 *
 * <p>Policy 2026-11-20 (owner: "make them a rule"; PROVISIONAL, pending a named obstetric clinician's
 * sign-off): for the pregnant or postpartum woman the maternal early warning stands in NEWS2's place
 * ({@link MaternalEarlyWarningService}: the NHS England national MEWS, the CRADLE add-on alerts). It
 * is what the chart and the advisory show, what sets her vitals round and what pages; NEWS2 neither
 * shows nor pages for her. With the maternal score switched off, the #1237 safety net is back as it
 * was: « NEWS2 non validé », and the NEWS2 page at its usual trigger. A newborn keeps the newborn
 * rules: no newborn score is added here.
 */
@Component
@RequiredArgsConstructor
@ProvisionalRule(ClinicalRuleSetKey.MATERNAL_EARLY_WARNING)
public class News2Applicability {

    /** Why a score is or is not shown. */
    public enum Status {
        /** An adult outside pregnancy and the postpartum window: the score is shown. */
        APPLIES,
        /** Under 16 (QA D518): no score, as before. */
        CHILD,
        /** Under 28 days: no score; the newborn rules apply. */
        NEWBORN,
        /** Pregnant or within the postpartum window: no reassuring score; the maternity alerts apply. */
        OBSTETRIC;

        /** The patient is told why there is no score (a child simply has none, as before D2580). */
        public boolean notValidated() {
            return this == NEWBORN || this == OBSTETRIC;
        }
    }

    private final SevereHypertension severeHypertension;

    /** Policy 2026-11-20: the maternal rule set (setter-injected; absent, its defaults: on). */
    private MaternalEarlyWarningRules maternalRules = new MaternalEarlyWarningRules();

    @org.springframework.beans.factory.annotation.Autowired
    void setMaternalRules(MaternalEarlyWarningRules maternalRules) {
        this.maternalRules = maternalRules;
    }

    /**
     * Policy 2026-11-20: the status with, for an OBSTETRIC one, the birth that dates the postpartum
     * window (null in pregnancy). The maternal early-warning score reads it to switch its pulse table.
     */
    public record Applicability(Status status, LocalDateTime birthAt, UUID sourceHospitalId) {
        public Applicability(Status status, LocalDateTime birthAt) {
            this(status, birthAt, null);
        }
    }

    /**
     * A maternal warning raised for an OBSTETRIC status that rests on another hospital's record is
     * accounted in the reach ledger (the shared rule does it, as for its own pressure warning).
     */
    public void accountForeignRecord(UUID patientId, UUID hospitalId, Applicability known) {
        if (known != null && known.status() == Status.OBSTETRIC && known.sourceHospitalId() != null) {
            severeHypertension.accountForeignRecord(patientId, hospitalId, known.sourceHospitalId());
        }
    }

    /** The status of a reading taken at {@code at} for {@code patient}, read at {@code hospitalId}. */
    public Status statusOf(Patient patient, UUID hospitalId, LocalDateTime at) {
        return applicabilityOf(patient, hospitalId, at).status();
    }

    /** {@link #statusOf}, with the birth behind a postpartum scope: one read of the obstetric record. */
    public Applicability applicabilityOf(Patient patient, UUID hospitalId, LocalDateTime at) {
        if (patient == null) {
            return new Applicability(Status.APPLIES, null);
        }
        LocalDateTime when = at != null ? at : LocalDateTime.now(ZoneId.systemDefault());
        if (VitalSignJudgement.isNewborn(patient.getDateOfBirth(), when)) {
            return new Applicability(Status.NEWBORN, null);
        }
        // Switched off, the age is asked first, as before the policy: a child has no score, and her
        // obstetric record is not read.
        if (!maternalRules.isEnabled() && !NewsScoreCalculator.appliesTo(patient)) {
            return new Applicability(Status.CHILD, null);
        }
        // Policy 2026-11-20 (provisional): the obstetric scope is asked before the age. A girl under 16 who is
        // pregnant or has given birth is an obstetric patient: she had no score at all (the adult NEWS2 never
        // applied to her) and no early-warning page in labour; the maternal early warning now covers her.
        SevereHypertension.ObstetricContext context = severeHypertension.obstetricContextOf(patient, hospitalId, when);
        if (context != null && context.obstetric()) {
            return new Applicability(Status.OBSTETRIC, context.birthAt(), context.sourceHospitalId());
        }
        return new Applicability(NewsScoreCalculator.appliesTo(patient) ? Status.APPLIES : Status.CHILD, null);
    }
}
