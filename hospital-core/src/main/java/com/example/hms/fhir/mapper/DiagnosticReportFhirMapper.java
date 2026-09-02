package com.example.hms.fhir.mapper;

import com.example.hms.enums.ImagingReportStatus;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.MicroCultureStatus;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.MicroCultureResult;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * Maps the three report domains into FHIR R4 {@code DiagnosticReport}
 * (Tier 2 item 42):
 * <ul>
 *   <li>{@code laborder-{uuid}} — one lab order with its result rows, each
 *       result referenced as {@code Observation/labresult-{uuid}} so the
 *       report and the Observation provider agree on ids;</li>
 *   <li>{@code micro-{uuid}} — one culture ({@code MB} category);</li>
 *   <li>{@code imgreport-{uuid}} — one signed-or-preliminary imaging read
 *       ({@code RAD} category).</li>
 * </ul>
 *
 * <p>Read-only. Reports are authored and signed through the surfaces that
 * enforce the ceremonies (#26's sign path, micro finalization); FHIR is a
 * face on them, never a way around them.
 */
@Component
public class DiagnosticReportFhirMapper {

    /** HL7 v2-0074 diagnostic service sections — the standard category axis. */
    private static final String V2_0074 = "http://terminology.hl7.org/CodeSystem/v2-0074";

    // ── Lab order + results ─────────────────────────────────────────────

    public DiagnosticReport toFhir(LabOrder order, List<LabResult> results) {
        if (order == null || order.getId() == null) return null;
        DiagnosticReport report = new DiagnosticReport();
        report.setId("laborder-" + order.getId());
        report.setStatus(labStatus(order, results));
        report.addCategory(category("LAB", "Laboratory"));
        report.setCode(LabCodes.codeFor(order.getLabTestDefinition(), "Laboratory report"));
        if (order.getPatient() != null && order.getPatient().getId() != null) {
            report.setSubject(new Reference("Patient/" + order.getPatient().getId()));
        }
        if (order.getEncounter() != null && order.getEncounter().getId() != null) {
            report.setEncounter(new Reference("Encounter/" + order.getEncounter().getId()));
        }
        report.setEffective(dateTime(order.getOrderDatetime()));
        if (results != null) {
            results.stream()
                .filter(r -> r != null && r.getId() != null)
                .forEach(r -> report.addResult(new Reference("Observation/labresult-" + r.getId())));
            results.stream()
                .filter(r -> r != null && r.getResultDate() != null)
                .map(LabResult::getResultDate)
                .max(Comparator.naturalOrder())
                .ifPresent(latest -> report.setIssuedElement(
                    new org.hl7.fhir.r4.model.InstantType(
                        Date.from(latest.atZone(ZoneId.systemDefault()).toInstant()))));
        }
        return report;
    }

    /**
     * The report's status is the results', not the order's: an order can sit
     * COMPLETED administratively while a correction is still moving, and the
     * consumer of a DiagnosticReport is asking about the results.
     * No results at all is {@code registered} — the order exists, nothing is
     * reportable yet. A cancelled order is {@code cancelled} regardless.
     */
    private static DiagnosticReport.DiagnosticReportStatus labStatus(
        LabOrder order, List<LabResult> results) {
        if (order.getStatus() == LabOrderStatus.CANCELLED) {
            return DiagnosticReport.DiagnosticReportStatus.CANCELLED;
        }
        if (results == null || results.isEmpty()) {
            return DiagnosticReport.DiagnosticReportStatus.REGISTERED;
        }
        boolean allReleased = results.stream().allMatch(LabResult::isReleased);
        boolean anyReleased = results.stream().anyMatch(LabResult::isReleased);
        if (allReleased) return DiagnosticReport.DiagnosticReportStatus.FINAL;
        if (anyReleased) return DiagnosticReport.DiagnosticReportStatus.PARTIAL;
        return DiagnosticReport.DiagnosticReportStatus.PRELIMINARY;
    }

    // ── Microbiology culture ────────────────────────────────────────────

    public DiagnosticReport toFhir(MicroCultureResult culture) {
        if (culture == null || culture.getId() == null) return null;
        DiagnosticReport report = new DiagnosticReport();
        report.setId("micro-" + culture.getId());
        report.setStatus(microStatus(culture.getStatus()));
        report.addCategory(category("MB", "Microbiology"));
        CodeableConcept code = new CodeableConcept().setText(
            culture.getSpecimenSource() != null && !culture.getSpecimenSource().isBlank()
                ? "Culture — " + culture.getSpecimenSource()
                : "Microbiology culture");
        report.setCode(code);
        if (culture.getPatient() != null && culture.getPatient().getId() != null) {
            report.setSubject(new Reference("Patient/" + culture.getPatient().getId()));
        }
        report.setEffective(dateTime(culture.getCollectedAt()));
        if (culture.getFinalizedAt() != null) {
            report.setIssuedElement(new org.hl7.fhir.r4.model.InstantType(
                Date.from(culture.getFinalizedAt().atZone(ZoneId.systemDefault()).toInstant())));
        }
        StringBuilder conclusion = new StringBuilder();
        if (culture.getGrowthResult() != null) {
            conclusion.append(culture.getGrowthResult().name().replace('_', ' '));
        }
        if (culture.getGramStain() != null && !culture.getGramStain().isBlank()) {
            if (conclusion.length() > 0) conclusion.append("; ");
            conclusion.append("Gram stain: ").append(culture.getGramStain());
        }
        if (conclusion.length() > 0) {
            report.setConclusion(conclusion.toString());
        }
        if (culture.getNotes() != null && !culture.getNotes().isBlank()) {
            // Notes stay notes: a culture's free text is commentary, and
            // folding it into the conclusion would present it as the read.
            report.getText().setDivAsString("<div>" + culture.getNotes() + "</div>");
        }
        return report;
    }

    private static DiagnosticReport.DiagnosticReportStatus microStatus(MicroCultureStatus status) {
        if (status == null) return DiagnosticReport.DiagnosticReportStatus.PRELIMINARY;
        return switch (status) {
            case PRELIMINARY -> DiagnosticReport.DiagnosticReportStatus.PRELIMINARY;
            case FINAL -> DiagnosticReport.DiagnosticReportStatus.FINAL;
            case CORRECTED -> DiagnosticReport.DiagnosticReportStatus.CORRECTED;
        };
    }

    // ── Imaging report ──────────────────────────────────────────────────

    public DiagnosticReport toFhir(ImagingReport src) {
        if (src == null || src.getId() == null) return null;
        DiagnosticReport report = new DiagnosticReport();
        report.setId("imgreport-" + src.getId());
        report.setStatus(imagingStatus(src.getReportStatus()));
        report.addCategory(category("RAD", "Radiology"));
        CodeableConcept code = new CodeableConcept();
        if (src.getReportTitle() != null && !src.getReportTitle().isBlank()) {
            code.setText(src.getReportTitle());
        } else {
            code.setText("Imaging report");
        }
        if (src.getModality() != null) {
            code.addCoding(new Coding()
                .setSystem("urn:hms:imaging:modality")
                .setCode(src.getModality().name()));
        }
        report.setCode(code);
        var patient = src.getImagingOrder() != null ? src.getImagingOrder().getPatient() : null;
        if (patient != null && patient.getId() != null) {
            report.setSubject(new Reference("Patient/" + patient.getId()));
        }
        report.setEffective(dateTime(src.getPerformedAt()));
        if (src.getSignedAt() != null) {
            report.setIssuedElement(new org.hl7.fhir.r4.model.InstantType(
                Date.from(src.getSignedAt().atZone(ZoneId.systemDefault()).toInstant())));
        }
        // The impression is the read; findings and technique are the body.
        // FHIR's conclusion field means "clinical conclusion", which is the
        // impression and only the impression.
        if (src.getImpression() != null && !src.getImpression().isBlank()) {
            report.setConclusion(src.getImpression());
        }
        return report;
    }

    private static DiagnosticReport.DiagnosticReportStatus imagingStatus(ImagingReportStatus status) {
        if (status == null) return DiagnosticReport.DiagnosticReportStatus.PRELIMINARY;
        return switch (status) {
            case DRAFT, PRELIMINARY -> DiagnosticReport.DiagnosticReportStatus.PRELIMINARY;
            case FINAL -> DiagnosticReport.DiagnosticReportStatus.FINAL;
            case ADDENDUM -> DiagnosticReport.DiagnosticReportStatus.APPENDED;
            case CORRECTED -> DiagnosticReport.DiagnosticReportStatus.CORRECTED;
            case AMENDED -> DiagnosticReport.DiagnosticReportStatus.AMENDED;
            case CANCELLED -> DiagnosticReport.DiagnosticReportStatus.CANCELLED;
            case ERROR -> DiagnosticReport.DiagnosticReportStatus.ENTEREDINERROR;
        };
    }

    // ── shared ──────────────────────────────────────────────────────────

    private static CodeableConcept category(String code, String display) {
        return new CodeableConcept().addCoding(new Coding()
            .setSystem(V2_0074).setCode(code).setDisplay(display));
    }

    private static DateTimeType dateTime(LocalDateTime value) {
        if (value == null) return null;
        return new DateTimeType(Date.from(value.atZone(ZoneId.systemDefault()).toInstant()));
    }
}
