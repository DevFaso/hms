package com.example.hms.fhir.mapper;

import com.example.hms.enums.PrescriptionStatus;
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
        out.setStatus(statusOf(src));
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

    /**
     * QA D1537 review (round 40): the order's own status, never "active" for all. Every value is decided
     * here (the switch has no default, so a new status does not compile until it is):
     * <ul>
     *   <li>draft: a proposal (DRAFT, PENDING_SIGNATURE);</li>
     *   <li>cancelled: withdrawn as CANCELLED, or withdrawn before anyone signed it (FHIR: withdrawn
     *       before any administration);</li>
     *   <li>stopped: a signed order DISCONTINUED;</li>
     *   <li>completed: the medicine was dispensed (DISPENSED, PARTNER_DISPENSED), or a running order's
     *       stated duration has passed ({@code MedicationCourseStatus.endDate});</li>
     *   <li>on-hold: waiting on the pharmacy (PENDING_CLARIFICATION, PENDING_STOCK, TRANSMISSION_FAILED,
     *       PARTNER_REJECTED);</li>
     *   <li>active: every other signed order.</li>
     * </ul>
     */
    static MedicationRequest.MedicationRequestStatus statusOf(Prescription src) {
        PrescriptionStatus status = src.getStatus();
        if (status == null) {
            return MedicationRequest.MedicationRequestStatus.UNKNOWN;
        }
        if (status.isWithdrawn() && src.isWithdrawnBeforeSigning()) {
            return MedicationRequest.MedicationRequestStatus.CANCELLED;
        }
        MedicationRequest.MedicationRequestStatus mapped = switch (status) {
            case DRAFT, PENDING_SIGNATURE -> MedicationRequest.MedicationRequestStatus.DRAFT;
            case CANCELLED -> MedicationRequest.MedicationRequestStatus.CANCELLED;
            case DISCONTINUED -> MedicationRequest.MedicationRequestStatus.STOPPED;
            case DISPENSED, PARTNER_DISPENSED -> MedicationRequest.MedicationRequestStatus.COMPLETED;
            case PENDING_CLARIFICATION, PENDING_STOCK, TRANSMISSION_FAILED, PARTNER_REJECTED ->
                MedicationRequest.MedicationRequestStatus.ONHOLD;
            case SIGNED, TRANSMITTED, PARTIALLY_FILLED, SENT_TO_PARTNER, PARTNER_ACCEPTED, PRINTED_FOR_PATIENT ->
                MedicationRequest.MedicationRequestStatus.ACTIVE;
        };
        if (mapped == MedicationRequest.MedicationRequestStatus.ACTIVE) {
            java.time.LocalDate end = com.example.hms.service.MedicationCourseStatus.endDate(src);
            if (end != null && end.isBefore(java.time.LocalDate.now(ZoneId.systemDefault()))) {
                return MedicationRequest.MedicationRequestStatus.COMPLETED;
            }
        }
        return mapped;
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
        // QA D1378: the prescriber's explicit "as needed" tick, never read from the text.
        if (com.example.hms.service.impl.PrnMinInterval.isAsNeeded(src)) {
            d.setAsNeeded(new org.hl7.fhir.r4.model.BooleanType(true));
        }
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

    /**
     * Round 33: only one number is a structured dose. Stripping everything but digits read
     * "12,5" as 125 and "2 a 4" as 24. A range or any other free text stays in the
     * dosage's text, which already carries it.
     */
    private static void addStructuredDose(Dosage d, Prescription src) {
        if (src.getDoseUnit() == null || src.getDosage() == null) return;
        java.util.regex.Matcher single = SINGLE_DOSE.matcher(src.getDosage());
        if (!single.matches()) return;
        Quantity q = new Quantity()
            .setValue(new java.math.BigDecimal(single.group(1).replace(',', '.')))
            .setUnit(src.getDoseUnit());
        Dosage.DosageDoseAndRateComponent dr = new Dosage.DosageDoseAndRateComponent();
        dr.setDose(q);
        d.addDoseAndRate(dr);
    }

    /** One number, and at most one unit word after it. */
    private static final java.util.regex.Pattern SINGLE_DOSE =
        java.util.regex.Pattern.compile("\\s*+(\\d++(?:[.,]\\d++)?+)\\s*+\\p{L}*+\\s*+");
}
