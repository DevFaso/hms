package com.example.hms.fhir.mapper;

import com.example.hms.model.PatientImmunization;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Immunization;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.SimpleQuantity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Date;
import java.util.Locale;

/**
 * Maps {@link com.example.hms.model.PatientImmunization} → FHIR R4 {@code Immunization}.
 *
 * <p>Vaccine code uses CVX (CDC) when {@code vaccineCode} is present, else falls
 * back to a project-local CodeSystem. WHO/PAHO codes will plug in via the same
 * field once configured per West-Africa registry requirements (DHIS2 Tracker).
 */
@Component
public class ImmunizationFhirMapper {

    private static final String SYSTEM_CVX = "http://hl7.org/fhir/sid/cvx";
    private static final String SYSTEM_HMS_LOCAL = "urn:hms:vaccine:code";

    public Immunization toFhir(PatientImmunization src) {
        if (src == null) return null;
        Immunization out = new Immunization();
        out.setId(src.getId() == null ? null : src.getId().toString());
        out.setStatus(mapStatus(src));
        out.setVaccineCode(mapVaccineCode(src));

        if (src.getPatient() != null && src.getPatient().getId() != null) {
            out.setPatient(new Reference("Patient/" + src.getPatient().getId()));
        }
        if (src.getEncounter() != null && src.getEncounter().getId() != null) {
            out.setEncounter(new Reference("Encounter/" + src.getEncounter().getId()));
        }
        if (src.getAdministrationDate() != null) {
            out.setOccurrence(new DateTimeType(Date.from(
                src.getAdministrationDate().atStartOfDay(ZoneId.systemDefault()).toInstant()
            )));
        }
        if (src.getLotNumber() != null) out.setLotNumber(src.getLotNumber());
        if (src.getExpirationDate() != null) {
            out.setExpirationDate(Date.from(
                src.getExpirationDate().atStartOfDay(ZoneId.systemDefault()).toInstant()
            ));
        }
        if (src.getRoute() != null) {
            out.setRoute(new CodeableConcept().setText(src.getRoute()));
        }
        if (src.getSite() != null) {
            out.setSite(new CodeableConcept().setText(src.getSite()));
        }
        if (src.getDoseQuantity() != null && src.getDoseUnit() != null) {
            out.setDoseQuantity(new SimpleQuantity()
                .setValue(BigDecimal.valueOf(src.getDoseQuantity()))
                .setUnit(src.getDoseUnit()));
        }
        return out;
    }

    private static Immunization.ImmunizationStatus mapStatus(PatientImmunization src) {
        if (src.getStatus() == null) return Immunization.ImmunizationStatus.COMPLETED;
        return switch (src.getStatus().trim().toLowerCase(Locale.ROOT)) {
            case "completed", "administered" -> Immunization.ImmunizationStatus.COMPLETED;
            case "not_done", "notdone", "refused" -> Immunization.ImmunizationStatus.NOTDONE;
            case "entered_in_error", "entered-in-error" -> Immunization.ImmunizationStatus.ENTEREDINERROR;
            default -> Immunization.ImmunizationStatus.COMPLETED;
        };
    }

    private static CodeableConcept mapVaccineCode(PatientImmunization src) {
        CodeableConcept code = new CodeableConcept();
        if (src.getVaccineDisplay() != null) code.setText(src.getVaccineDisplay());
        if (src.getVaccineCode() != null && !src.getVaccineCode().isBlank()) {
            code.addCoding(new Coding()
                .setSystem(SYSTEM_CVX)
                .setCode(src.getVaccineCode())
                .setDisplay(src.getVaccineDisplay()));
        } else if (src.getVaccineType() != null) {
            code.addCoding(new Coding()
                .setSystem(SYSTEM_HMS_LOCAL)
                .setCode(src.getVaccineType())
                .setDisplay(src.getVaccineDisplay()));
        }
        return code;
    }
}
