package com.example.hms.fhir.mapper;

import com.example.hms.model.PatientProblem;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Date;
import java.util.Locale;

/**
 * Maps {@link com.example.hms.model.PatientProblem} → FHIR R4 {@code Condition}.
 *
 * <p>For West-Africa-friendly interoperability the source coding system is
 * derived from {@code icdVersion} (ICD-10 / ICD-11) when present and falls
 * back to a project-local CodeSystem when freetext is used. SNOMED CT can
 * be added once licensing is resolved per the gap analysis.
 */
@Component
public class ConditionFhirMapper {

    private static final String SYSTEM_ICD10 = "http://hl7.org/fhir/sid/icd-10";
    private static final String SYSTEM_ICD11 = "http://id.who.int/icd/release/11/mms";
    private static final String SYSTEM_HMS_LOCAL = "urn:hms:problem-code";
    private static final String CLINICAL_STATUS_SYSTEM =
        "http://terminology.hl7.org/CodeSystem/condition-clinical";
    private static final String VERIFICATION_STATUS_SYSTEM =
        "http://terminology.hl7.org/CodeSystem/condition-ver-status";

    public Condition toFhir(PatientProblem src) {
        if (src == null) return null;
        Condition out = new Condition();
        out.setId(src.getId() == null ? null : src.getId().toString());
        out.setSubject(patientReference(src));
        out.setRecordedDate(toDate(src));

        out.setCode(buildCode(src));
        out.setClinicalStatus(buildClinicalStatus(src));
        out.setVerificationStatus(buildVerificationStatus());
        addSeverity(out, src);
        if (src.getOnsetDate() != null) {
            out.setOnset(new DateTimeType(Date.from(
                src.getOnsetDate().atStartOfDay(ZoneId.systemDefault()).toInstant()
            )));
        }
        if (src.getNotes() != null && !src.getNotes().isBlank()) {
            out.addNote().setText(src.getNotes());
        }
        return out;
    }

    private static Reference patientReference(PatientProblem src) {
        if (src.getPatient() == null || src.getPatient().getId() == null) return null;
        return new Reference("Patient/" + src.getPatient().getId());
    }

    private static Date toDate(PatientProblem src) {
        if (src.getCreatedAt() == null) return null;
        return Date.from(src.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant());
    }

    private static CodeableConcept buildCode(PatientProblem src) {
        CodeableConcept code = new CodeableConcept();
        if (src.getProblemDisplay() != null) code.setText(src.getProblemDisplay());
        if (src.getProblemCode() != null && !src.getProblemCode().isBlank()) {
            code.addCoding(new Coding()
                .setSystem(systemFor(src.getIcdVersion()))
                .setCode(src.getProblemCode())
                .setDisplay(src.getProblemDisplay()));
        }
        return code;
    }

    private static String systemFor(String icdVersion) {
        if (icdVersion == null) return SYSTEM_HMS_LOCAL;
        String v = icdVersion.trim().toLowerCase(Locale.ROOT);
        if (v.contains("11")) return SYSTEM_ICD11;
        if (v.contains("10")) return SYSTEM_ICD10;
        return SYSTEM_HMS_LOCAL;
    }

    private static CodeableConcept buildClinicalStatus(PatientProblem src) {
        String code = src.getStatus() == null ? "active" : src.getStatus().name().toLowerCase(Locale.ROOT);
        // Normalize internal statuses (ACTIVE/RESOLVED/INACTIVE/RECURRENCE/REMISSION)
        // into FHIR's required value-set.
        String normalized = switch (code) {
            case "active", "recurrence", "relapse" -> "active";
            case "resolved"                         -> "resolved";
            case "inactive", "remission"           -> "inactive";
            default                                 -> "active";
        };
        return new CodeableConcept().addCoding(new Coding()
            .setSystem(CLINICAL_STATUS_SYSTEM).setCode(normalized).setDisplay(normalized));
    }

    private static CodeableConcept buildVerificationStatus() {
        return new CodeableConcept().addCoding(new Coding()
            .setSystem(VERIFICATION_STATUS_SYSTEM).setCode("confirmed").setDisplay("Confirmed"));
    }

    private static void addSeverity(Condition out, PatientProblem src) {
        if (src.getSeverity() == null) return;
        String code = src.getSeverity().name().toLowerCase(Locale.ROOT);
        out.setSeverity(new CodeableConcept().setText(code));
    }
}
