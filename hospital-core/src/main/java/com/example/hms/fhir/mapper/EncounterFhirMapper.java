package com.example.hms.fhir.mapper;

import com.example.hms.model.Encounter;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Date;

/**
 * Maps {@link com.example.hms.model.Encounter} → FHIR R4 {@code Encounter}.
 *
 * <p>Status mapping aligns with HL7 FHIR R4
 * {@code http://hl7.org/fhir/encounter-status} value set.
 * The class code follows {@code http://terminology.hl7.org/CodeSystem/v3-ActCode}.
 */
@Component
public class EncounterFhirMapper {

    public org.hl7.fhir.r4.model.Encounter toFhir(Encounter src) {
        if (src == null) return null;
        org.hl7.fhir.r4.model.Encounter out = new org.hl7.fhir.r4.model.Encounter();
        out.setId(src.getId() == null ? null : src.getId().toString());
        out.setStatus(mapStatus(src));
        out.setClass_(mapClass(src));
        out.setSubject(patientReference(src));
        out.setPeriod(mapPeriod(src));
        if (src.getChiefComplaint() != null && !src.getChiefComplaint().isBlank()) {
            CodeableConcept reason = new CodeableConcept();
            reason.setText(src.getChiefComplaint());
            out.addReasonCode(reason);
        }
        if (src.getNotes() != null && !src.getNotes().isBlank()) {
            out.addType(new CodeableConcept().setText(src.getNotes()));
        }
        return out;
    }

    private static org.hl7.fhir.r4.model.Encounter.EncounterStatus mapStatus(Encounter src) {
        if (src.getStatus() == null) return org.hl7.fhir.r4.model.Encounter.EncounterStatus.UNKNOWN;
        return switch (src.getStatus()) {
            case SCHEDULED            -> org.hl7.fhir.r4.model.Encounter.EncounterStatus.PLANNED;
            case ARRIVED              -> org.hl7.fhir.r4.model.Encounter.EncounterStatus.ARRIVED;
            case TRIAGE               -> org.hl7.fhir.r4.model.Encounter.EncounterStatus.TRIAGED;
            case WAITING_FOR_PHYSICIAN,
                 IN_PROGRESS,
                 AWAITING_RESULTS,
                 READY_FOR_DISCHARGE  -> org.hl7.fhir.r4.model.Encounter.EncounterStatus.INPROGRESS;
            case COMPLETED            -> org.hl7.fhir.r4.model.Encounter.EncounterStatus.FINISHED;
            case CANCELLED            -> org.hl7.fhir.r4.model.Encounter.EncounterStatus.CANCELLED;
        };
    }

    private static Coding mapClass(Encounter src) {
        Coding cls = new Coding().setSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode");
        if (src.getEncounterType() == null) {
            return cls.setCode("AMB").setDisplay("ambulatory");
        }
        return switch (src.getEncounterType()) {
            case INPATIENT       -> cls.setCode("IMP").setDisplay("inpatient encounter");
            case EMERGENCY       -> cls.setCode("EMER").setDisplay("emergency");
            case SURGERY         -> cls.setCode("ACUTE").setDisplay("inpatient acute");
            case CONSULTATION,
                 OUTPATIENT,
                 FOLLOW_UP,
                 LAB             -> cls.setCode("AMB").setDisplay("ambulatory");
            default              -> cls.setCode("AMB").setDisplay("ambulatory");
        };
    }

    private static Reference patientReference(Encounter src) {
        if (src.getPatient() == null || src.getPatient().getId() == null) return null;
        return new Reference("Patient/" + src.getPatient().getId());
    }

    private static Period mapPeriod(Encounter src) {
        Period p = new Period();
        if (src.getEncounterDate() != null) {
            p.setStart(Date.from(src.getEncounterDate().atZone(ZoneId.systemDefault()).toInstant()));
        }
        if (src.getCheckoutTimestamp() != null) {
            p.setEnd(Date.from(src.getCheckoutTimestamp().atZone(ZoneId.systemDefault()).toInstant()));
        }
        return (p.getStart() == null && p.getEnd() == null) ? null : p;
    }
}
