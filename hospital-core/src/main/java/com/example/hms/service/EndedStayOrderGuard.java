package com.example.hms.service;

import com.example.hms.enums.EncounterType;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.model.Prescription;
import com.example.hms.repository.AdmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * QA D792 (#953 review): the one question every pharmacy action asks before it
 * acts on an order (hand-over, dispense, verification, queue claim, routing,
 * SMS dispatch): did this order end with a stay? It applies
 * {@link InpatientOrderEnd#endedWithAnEndedStay}, the rule migration V233
 * ends orders by, so an order V233 held (a prepared fill, an open partner
 * routing) is refused whatever its shape, not only when it sits on a closed
 * stay encounter.
 *
 * <p>Answers from the order alone when it can (take-home, a closed stay
 * encounter, an outpatient visit); otherwise it reads the patient's stays at
 * the ORDER's own hospital, never at a hospital a caller names.
 */
@Component
@RequiredArgsConstructor
public class EndedStayOrderGuard {

    private final AdmissionRepository admissionRepository;

    public boolean endedWithAStay(Prescription rx) {
        if (rx == null || rx.isTakeHome()) {
            return false;
        }
        if (InpatientOrderEnd.endedWithItsStay(rx)) {
            return true;
        }
        Encounter encounter = rx.getEncounter();
        if (encounter != null && encounter.getAdmissionId() == null
            && encounter.getEncounterType() != EncounterType.INPATIENT
            && encounter.getEncounterType() != EncounterType.EMERGENCY) {
            return false;
        }
        UUID patientId = rx.getPatient() != null ? rx.getPatient().getId() : null;
        UUID hospitalId = rx.getHospital() != null ? rx.getHospital().getId() : null;
        if (patientId == null || hospitalId == null) {
            return false;
        }
        List<Admission> stays = admissionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId);
        return InpatientOrderEnd.endedWithAnEndedStay(rx, stays);
    }
}
