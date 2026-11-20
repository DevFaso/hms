package com.example.hms.service.recordaccess;

import com.example.hms.enums.SensitivityCategory;
import com.example.hms.model.Appointment;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Encounter;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.PatientImmunization;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.Prescription;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * QA D2730 (r65, High): the one rule for what a proxy (a caregiver holding a
 * family-access grant) may read of the grantor's record.
 *
 * <p><b>A proxy never receives a row whose effective sensitivity category is
 * set</b> (HIV, behavioural health, substance use, reproductive health). The
 * category is the codebase's existing one ({@link SensitivityClassifier}): the
 * row's explicit tag, else its visit's, else the department default. A lab
 * result also carries its test definition's tag, when the hospital set one.
 *
 * <p>No grant scope covers sensitive content today: VIEW_RECORDS, the other
 * VIEW_* scopes and ALL name the ordinary record only. So a proxy gets none of
 * it, whatever the grant says, until a scope that explicitly covers it exists.
 * The patient's own reads never come through here.
 *
 * <p>Every proxy read applies this filter; {@code ProxyReadSensitivityGuardTest}
 * fails when a new one does not.
 */
@Component
@RequiredArgsConstructor
public class ProxyRecordFilter {

    private final SensitivityClassifier sensitivityClassifier;

    /** The rule itself: only an untagged row reaches a proxy. */
    public boolean admits(SensitivityCategory effectiveCategory) {
        return effectiveCategory == null;
    }

    /** A visit, by its explicit tag or its department's default. */
    public boolean admits(Encounter encounter) {
        return admits(sensitivityClassifier.effectiveCategory(encounter));
    }

    /** A result inherits its order's visit and its test definition's tag. */
    public boolean admits(LabResult result) {
        return result == null || admits(result.getLabOrder());
    }

    public boolean admits(LabOrder order) {
        if (order == null) {
            return true;
        }
        LabTestDefinition definition = order.getLabTestDefinition();
        if (definition != null && definition.getSensitivityCategory() != null) {
            return false;
        }
        return admits(order.getEncounter());
    }

    /** A prescription written in a sensitive visit is sensitive. */
    public boolean admits(Prescription prescription) {
        return prescription == null || admits(prescription.getEncounter());
    }

    /** A problem carries its own tag only. */
    public boolean admits(PatientProblem problem) {
        return admits(sensitivityClassifier.effectiveCategory(problem));
    }

    public boolean admits(PatientImmunization immunization) {
        return immunization == null || admits(immunization.getEncounter());
    }

    /** An invoice names the visit's services; it follows its visit. */
    public boolean admits(BillingInvoice invoice) {
        return invoice == null || admits(invoice.getEncounter());
    }

    /**
     * An appointment reveals its department, so a department that defaults to
     * a sensitive category withholds it. The visit it opened, when there is
     * one, is judged by the caller (it is the owning side of the link).
     */
    public boolean admits(Appointment appointment) {
        return appointment == null
            || admits(sensitivityClassifier.effectiveCategory(null, appointment.getDepartment()));
    }
}
