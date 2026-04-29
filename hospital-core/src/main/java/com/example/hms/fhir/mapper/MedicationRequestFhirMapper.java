package com.example.hms.fhir.mapper;

import com.example.hms.model.Prescription;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Dosage;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.SimpleQuantity;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Date;

/**
 * Maps {@link com.example.hms.model.Prescription} → FHIR R4 {@code MedicationRequest}.
 *
 * <p>Until terminology binding lands (gap #5) the medication code falls back to
 * the project-local {@code urn:hms:medication:code} system. RxNorm and WHO ATC
 * coding will be added once {@code MedicationCatalogItem} carries those fields.
 */
@Component
public class MedicationRequestFhirMapper {

    private static final String SYSTEM_RXNORM = "http://www.nlm.nih.gov/research/umls/rxnorm";
    private static final String SYSTEM_HMS_LOCAL = "urn:hms:medication:code";

    public MedicationRequest toFhir(Prescription src) {
        if (src == null) return null;
        MedicationRequest out = new MedicationRequest();
        out.setId(src.getId() == null ? null : src.getId().toString());
        out.setStatus(MedicationRequest.MedicationRequestStatus.ACTIVE);
        out.setIntent(MedicationRequest.MedicationRequestIntent.ORDER);

        if (src.getPatient() != null && src.getPatient().getId() != null) {
            out.setSubject(new Reference("Patient/" + src.getPatient().getId()));
        }
        if (src.getEncounter() != null && src.getEncounter().getId() != null) {
            out.setEncounter(new Reference("Encounter/" + src.getEncounter().getId()));
        }
        if (src.getCreatedAt() != null) {
            out.setAuthoredOn(Date.from(src.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant()));
        }

        out.setMedication(buildMedicationCode(src));
        out.addDosageInstruction(buildDosage(src));
        if (src.getQuantity() != null) {
            SimpleQuantity qty = new SimpleQuantity();
            qty.setValue(src.getQuantity());
            if (src.getQuantityUnit() != null && !src.getQuantityUnit().isBlank()) {
                qty.setUnit(src.getQuantityUnit().trim());
            }
            MedicationRequest.MedicationRequestDispenseRequestComponent disp =
                new MedicationRequest.MedicationRequestDispenseRequestComponent();
            disp.setQuantity(qty);
            if (src.getRefillsAllowed() != null) {
                disp.setNumberOfRepeatsAllowed(src.getRefillsAllowed());
            }
            out.setDispenseRequest(disp);
        }
        return out;
    }

    private static CodeableConcept buildMedicationCode(Prescription src) {
        CodeableConcept code = new CodeableConcept();
        if (src.getMedicationDisplayName() != null && !src.getMedicationDisplayName().isBlank()) {
            code.setText(src.getMedicationDisplayName());
        } else if (src.getMedicationName() != null) {
            code.setText(src.getMedicationName());
        }
        if (src.getMedicationCode() != null && !src.getMedicationCode().isBlank()) {
            code.addCoding(new Coding()
                .setSystem(looksLikeRxNorm(src.getMedicationCode()) ? SYSTEM_RXNORM : SYSTEM_HMS_LOCAL)
                .setCode(src.getMedicationCode())
                .setDisplay(src.getMedicationName()));
        }
        return code;
    }

    private static boolean looksLikeRxNorm(String code) {
        return code.chars().allMatch(Character::isDigit);
    }

    private static Dosage buildDosage(Prescription src) {
        Dosage d = new Dosage();
        d.setText(renderDosageText(src));
        if (src.getRoute() != null) {
            d.setRoute(new CodeableConcept().setText(src.getRoute().trim()));
        }
        if (src.getInstructions() != null && !src.getInstructions().isBlank()) {
            d.setPatientInstruction(src.getInstructions());
        }
        addStructuredDose(d, src);
        return d;
    }

    private static String renderDosageText(Prescription src) {
        StringBuilder text = new StringBuilder();
        appendIfPresent(text, src.getDosage(), " ");
        appendIfPresent(text, src.getRoute(), " ");
        appendIfPresent(text, src.getFrequency(), " ");
        appendIfPresent(text, src.getDuration(), " — ");
        return text.isEmpty() ? null : text.toString();
    }

    private static void appendIfPresent(StringBuilder sink, String value, String separator) {
        if (value == null || value.isBlank()) return;
        if (!sink.isEmpty()) sink.append(separator);
        sink.append(value.trim());
    }

    private static void addStructuredDose(Dosage d, Prescription src) {
        if (src.getDoseUnit() == null || src.getDosage() == null) return;
        try {
            Quantity q = new Quantity()
                .setValue(new java.math.BigDecimal(src.getDosage().replaceAll("[^0-9.\\-]", "")))
                .setUnit(src.getDoseUnit());
            Dosage.DosageDoseAndRateComponent dr = new Dosage.DosageDoseAndRateComponent();
            dr.setDose(q);
            d.addDoseAndRate(dr);
        } catch (RuntimeException ignored) {
            // freetext dosage — text already captures it
        }
    }
}
