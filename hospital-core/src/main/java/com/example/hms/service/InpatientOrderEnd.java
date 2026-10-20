package com.example.hms.service;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.model.Prescription;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * QA D136: the one rule, shared by the MAR and the patient's medication list,
 * for when an inpatient order ended with its stay. An order is a stay's order
 * when it is filed on the stay's own encounter (V193) and that encounter is
 * closed, or, whoever wrote it, when it was written at the stay's hospital
 * between the admission and the discharge of a stay that has since ended.
 */
public final class InpatientOrderEnd {

    private InpatientOrderEnd() {
    }

    public static boolean endedWithAStay(Prescription rx, List<Admission> stays) {
        Encounter encounter = rx.getEncounter();
        if (encounter != null && encounter.getAdmissionId() != null
            && (encounter.getStatus() == EncounterStatus.COMPLETED
                || encounter.getStatus() == EncounterStatus.CANCELLED)) {
            return true;
        }
        // QA D316: an order written on an outpatient visit is the visit's,
        // not a stay's, even when a stay at the same hospital happened to
        // span that moment (a same-day admission and discharge ended every
        // prescription the patient was given that day). The time window
        // below is for orders that name no encounter, and for INPATIENT
        // encounters with no admission link (V193 did not backfill
        // admission_id, so an older stay's encounter carries none).
        if (encounter != null && encounter.getAdmissionId() == null
            && encounter.getEncounterType() != EncounterType.INPATIENT) {
            return false;
        }
        LocalDateTime written = rx.getCreatedAt();
        if (written == null || stays == null) return false;
        for (Admission stay : stays) {
            LocalDateTime from = stay.getAdmissionDateTime();
            LocalDateTime to = stay.getActualDischargeDateTime();
            if (from == null || to == null || written.isBefore(from) || written.isAfter(to)) continue;
            if (sameHospital(rx, stay)) return true;
        }
        return false;
    }

    private static boolean sameHospital(Prescription rx, Admission stay) {
        if (rx.getHospital() == null || stay.getHospital() == null) return true;
        return Objects.equals(rx.getHospital().getId(), stay.getHospital().getId());
    }
}
