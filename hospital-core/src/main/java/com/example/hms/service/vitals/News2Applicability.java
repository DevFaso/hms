package com.example.hms.service.vitals;

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
 * The NEWS2 page itself still fires at its usual trigger as a safety net (in labour it is the
 * only page for a low pressure or a fast pulse), and a score never lengthens the vitals round.
 * No obstetric early-warning thresholds (MEOWS-type) are added here: that needs clinical sign-off.
 */
@Component
@RequiredArgsConstructor
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

    /** The status of a reading taken at {@code at} for {@code patient}, read at {@code hospitalId}. */
    public Status statusOf(Patient patient, UUID hospitalId, LocalDateTime at) {
        if (patient == null) {
            return Status.APPLIES;
        }
        LocalDateTime when = at != null ? at : LocalDateTime.now(ZoneId.systemDefault());
        if (VitalSignJudgement.isNewborn(patient.getDateOfBirth(), when)) {
            return Status.NEWBORN;
        }
        if (!NewsScoreCalculator.appliesTo(patient)) {
            return Status.CHILD;
        }
        SevereHypertension.Scope scope = severeHypertension.scopeOf(patient, hospitalId, when);
        return scope == SevereHypertension.Scope.PREGNANT || scope == SevereHypertension.Scope.POSTPARTUM
            ? Status.OBSTETRIC : Status.APPLIES;
    }
}
