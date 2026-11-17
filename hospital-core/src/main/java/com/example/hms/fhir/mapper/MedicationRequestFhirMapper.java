package com.example.hms.fhir.mapper;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.Prescription;
import com.example.hms.service.MedicationCourseStatus;
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
import java.util.List;
import java.util.UUID;

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

    /**
     * QA D1537 review: the discharged stays an inpatient order ends with ({@code InpatientOrderEnd}), read
     * as the portal reads them. Optional so a mapper built by hand (tests) reads no stays.
     */
    private com.example.hms.repository.AdmissionRepository admissionRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAdmissionRepository(com.example.hms.repository.AdmissionRepository admissionRepository) {
        this.admissionRepository = admissionRepository;
    }

    /**
     * The patient's discharged stays, read once per patient by a caller mapping many orders
     * ({@link #toFhir(Prescription, List)}): the same list the portal's medication list reads.
     */
    public List<Admission> closedStaysOf(UUID patientId) {
        if (patientId == null || admissionRepository == null) {
            return List.of();
        }
        // QA D1537 review: lean rows, not every admission through its entity graph.
        return admissionRepository.findClosedStayRows(patientId).stream()
            .map(com.example.hms.repository.ClosedStayRow::toAdmission)
            .toList();
    }

    /** One order: its patient's stays are read here. A caller mapping many passes them in. */
    public MedicationRequest toFhir(Prescription src) {
        if (src == null) return null;
        return toFhir(src, closedStaysOf(src.getPatient() == null ? null : src.getPatient().getId()));
    }

    public MedicationRequest toFhir(Prescription src, List<Admission> closedStays) {
        if (src == null) return null;
        MedicationRequest out = new MedicationRequest();
        out.setId(src.getId() == null ? null : src.getId().toString());
        out.setStatus(statusOf(src, closedStays));
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
     * QA D1537 review (round 40): the order's own status, never "active" for all. The switch names
     * every PrescriptionStatus and has no default, so a new status does not compile until it is decided:
     * <ul>
     *   <li>draft: DRAFT, PENDING_SIGNATURE. Never exported (every reader chooses issued orders only);
     *       named because the switch is exhaustive;</li>
     *   <li>cancelled: CANCELLED (FHIR: withdrawn before any administration). A proposal withdrawn
     *       before signing is never exported either, for the same reason;</li>
     *   <li>stopped: DISCONTINUED;</li>
     *   <li>on-hold: not in force, waiting on the pharmacy or the prescriber (TRANSMISSION_FAILED,
     *       PENDING_CLARIFICATION, PARTNER_REJECTED);</li>
     *   <li>every other signed order: active, or completed when the course status the patient's portal
     *       shows says so ({@code MedicationCourseStatus.of}, with the discharged stays it ends with): its
     *       stated duration passed, or it ended with its stay.</li>
     * </ul>
     */
    static MedicationRequest.MedicationRequestStatus statusOf(Prescription src, List<Admission> closedStays) {
        return switch (src.getStatus()) {
            case DRAFT, PENDING_SIGNATURE -> MedicationRequest.MedicationRequestStatus.DRAFT;
            case CANCELLED -> MedicationRequest.MedicationRequestStatus.CANCELLED;
            case DISCONTINUED -> MedicationRequest.MedicationRequestStatus.STOPPED;
            // PARTNER_REJECTED means "held for review" after a partner's refusal or a recall: on-hold
            // for now (whether it is stopped or cancelled is standing debt, tasklist.md round 40).
            case TRANSMISSION_FAILED, PENDING_CLARIFICATION, PARTNER_REJECTED ->
                MedicationRequest.MedicationRequestStatus.ONHOLD;
            case SIGNED, TRANSMITTED, PARTIALLY_FILLED, PENDING_STOCK, SENT_TO_PARTNER, PARTNER_ACCEPTED,
                 PRINTED_FOR_PATIENT, DISPENSED, PARTNER_DISPENSED ->
                MedicationCourseStatus.COMPLETED.equals(
                    MedicationCourseStatus.of(src, closedStays == null ? List.of() : closedStays))
                    ? MedicationRequest.MedicationRequestStatus.COMPLETED
                    : MedicationRequest.MedicationRequestStatus.ACTIVE;
        };
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
