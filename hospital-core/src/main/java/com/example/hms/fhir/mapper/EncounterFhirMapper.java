package com.example.hms.fhir.mapper;

import com.example.hms.model.Encounter;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
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
        // Encounter.type is reserved for classification (ADMS, NEWPT, etc.).
        // Free-text encounter notes will surface as a separate DocumentReference
        // once the documentation provider is added; mapping them onto type here
        // would mislead consumers that treat the field as a coded concept.
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

    private static final String CLASS_SYSTEM = "http://terminology.hl7.org/CodeSystem/v3-ActCode";
    private static final String CLASS_AMBULATORY = "ambulatory";

    private static Coding mapClass(Encounter src) {
        Coding cls = new Coding().setSystem(CLASS_SYSTEM);
        if (src.getEncounterType() == null) {
            return cls.setCode("AMB").setDisplay(CLASS_AMBULATORY);
        }
        return switch (src.getEncounterType()) {
            case INPATIENT       -> cls.setCode("IMP").setDisplay("inpatient encounter");
            case EMERGENCY       -> cls.setCode("EMER").setDisplay("emergency");
            case SURGERY         -> cls.setCode("ACUTE").setDisplay("inpatient acute");
            case TELEHEALTH      -> cls.setCode("VR").setDisplay("virtual");
            case CONSULTATION,
                 OUTPATIENT,
                 FOLLOW_UP,
                 LAB             -> cls.setCode("AMB").setDisplay(CLASS_AMBULATORY);
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

    /* ===================== Write direction (FHIR → entity) ===================== */

    /**
     * Apply the FHIR-mutable subset of fields from an inbound FHIR
     * Encounter onto an existing entity. Returns the same entity for
     * chaining. The caller is responsible for persisting + audit
     * emission.
     *
     * <p><strong>Intentionally narrow.</strong> Honored fields:
     * <ul>
     *   <li>{@code Encounter.period.end} → {@code checkoutTimestamp}.
     *       Only applied when the entity does not already carry a
     *       checkout timestamp; this path is the documented external
     *       "close the encounter" affordance and must not silently
     *       overwrite a value the clinical UI already set.</li>
     *   <li>{@code Encounter.reasonCode[0].text} → {@code chiefComplaint}.
     *       Only applied when the entity's current value is null or
     *       blank — chief complaint is captured at triage and external
     *       systems must not overwrite the clinician's note.</li>
     * </ul>
     *
     * <p><strong>Not honored:</strong> status (the encounter
     * state-machine fires timestamps + side-effects we do not want to
     * shortcut), class, type, subject, period.start, participants,
     * diagnoses, hospitalization. These belong to admin / clinical
     * workflows that carry their own audit and validation.
     */
    public Encounter applyFhirUpdates(Encounter existing, org.hl7.fhir.r4.model.Encounter src) {
        if (existing == null || src == null) return existing;
        applyCheckoutFromPeriodEnd(existing, src);
        applyChiefComplaintFromReasonCode(existing, src);
        return existing;
    }

    private static void applyCheckoutFromPeriodEnd(Encounter out, org.hl7.fhir.r4.model.Encounter src) {
        if (!src.hasPeriod()) return;
        Period p = src.getPeriod();
        if (p == null || !p.hasEnd() || p.getEnd() == null) return;
        if (out.getCheckoutTimestamp() != null) return;
        LocalDateTime checkout = LocalDateTime.ofInstant(p.getEnd().toInstant(), ZoneId.systemDefault());
        out.setCheckoutTimestamp(checkout);
    }

    private static void applyChiefComplaintFromReasonCode(Encounter out, org.hl7.fhir.r4.model.Encounter src) {
        if (!src.hasReasonCode() || src.getReasonCode().isEmpty()) return;
        if (out.getChiefComplaint() != null && !out.getChiefComplaint().isBlank()) return;
        CodeableConcept first = src.getReasonCodeFirstRep();
        if (first == null || first.getText() == null) return;
        String text = first.getText().trim();
        if (text.isEmpty()) return;
        out.setChiefComplaint(text);
    }
}
