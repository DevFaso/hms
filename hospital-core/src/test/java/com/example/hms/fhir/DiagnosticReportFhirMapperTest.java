package com.example.hms.fhir;

import com.example.hms.enums.ImagingReportStatus;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.MicroCultureStatus;
import com.example.hms.enums.MicroGrowthResult;
import com.example.hms.fhir.mapper.DiagnosticReportFhirMapper;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.MicroCultureResult;
import com.example.hms.model.Patient;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DiagnosticReport mapping (Tier 2 item 42).
 *
 * <p>The status-derivation tests are the ones that matter: a client reading
 * {@code final} on a report whose results are still unreleased would act on
 * numbers the lab has not stood behind yet.
 */
class DiagnosticReportFhirMapperTest {

    private final DiagnosticReportFhirMapper mapper = new DiagnosticReportFhirMapper();

    private static LabOrder order(LabOrderStatus status) {
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setStatus(status);
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        order.setPatient(patient);
        return order;
    }

    private static LabResult result(boolean released) {
        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setReleased(released);
        return result;
    }

    @Test
    @DisplayName("all results released reads FINAL")
    void allReleasedIsFinal() {
        DiagnosticReport report = mapper.toFhir(order(LabOrderStatus.COMPLETED),
            List.of(result(true), result(true)));
        assertThat(report.getStatus()).isEqualTo(DiagnosticReport.DiagnosticReportStatus.FINAL);
    }

    @Test
    @DisplayName("a mix of released and unreleased reads PARTIAL, never FINAL")
    void mixedIsPartial() {
        DiagnosticReport report = mapper.toFhir(order(LabOrderStatus.RESULTED),
            List.of(result(true), result(false)));
        assertThat(report.getStatus()).isEqualTo(DiagnosticReport.DiagnosticReportStatus.PARTIAL);
    }

    @Test
    @DisplayName("nothing released yet reads PRELIMINARY even on a COMPLETED order")
    void unreleasedIsPreliminaryRegardlessOfOrderStatus() {
        // The order can be closed administratively while the results are
        // still unreleased; the report's status is the results', not the
        // order's.
        DiagnosticReport report = mapper.toFhir(order(LabOrderStatus.COMPLETED),
            List.of(result(false)));
        assertThat(report.getStatus())
            .isEqualTo(DiagnosticReport.DiagnosticReportStatus.PRELIMINARY);
    }

    @Test
    @DisplayName("no results is REGISTERED; a cancelled order is CANCELLED")
    void emptyAndCancelled() {
        assertThat(mapper.toFhir(order(LabOrderStatus.ORDERED), List.of()).getStatus())
            .isEqualTo(DiagnosticReport.DiagnosticReportStatus.REGISTERED);
        assertThat(mapper.toFhir(order(LabOrderStatus.CANCELLED), List.of(result(true))).getStatus())
            .isEqualTo(DiagnosticReport.DiagnosticReportStatus.CANCELLED);
    }

    @Test
    @DisplayName("result references use the Observation provider's labresult namespace")
    void resultReferencesMatchObservationIds() {
        LabResult r = result(true);
        DiagnosticReport report = mapper.toFhir(order(LabOrderStatus.COMPLETED), List.of(r));
        assertThat(report.getResult()).hasSize(1);
        assertThat(report.getResult().get(0).getReference())
            .isEqualTo("Observation/labresult-" + r.getId());
    }

    @Test
    @DisplayName("a culture carries MB category, growth and gram stain in the conclusion")
    void microCulture() {
        MicroCultureResult culture = new MicroCultureResult();
        culture.setId(UUID.randomUUID());
        culture.setStatus(MicroCultureStatus.CORRECTED);
        culture.setGrowthResult(MicroGrowthResult.NO_GROWTH);
        culture.setGramStain("Gram-negative rods");
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        culture.setPatient(patient);

        DiagnosticReport report = mapper.toFhir(culture);

        assertThat(report.getIdElement().getIdPart()).isEqualTo("micro-" + culture.getId());
        assertThat(report.getStatus()).isEqualTo(DiagnosticReport.DiagnosticReportStatus.CORRECTED);
        assertThat(report.getCategory().get(0).getCoding().get(0).getCode()).isEqualTo("MB");
        assertThat(report.getConclusion()).isEqualTo("NO GROWTH; Gram stain: Gram-negative rods");
    }

    @Test
    @DisplayName("an imaging read maps every lifecycle state and keeps the impression as the conclusion")
    void imagingReport() {
        ImagingReport src = new ImagingReport();
        src.setId(UUID.randomUUID());
        src.setReportStatus(ImagingReportStatus.ADDENDUM);
        src.setImpression("No acute intracranial abnormality.");
        src.setFindings("Long narrative that is NOT the conclusion.");
        ImagingOrder imagingOrder = new ImagingOrder();
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        imagingOrder.setPatient(patient);
        src.setImagingOrder(imagingOrder);

        DiagnosticReport report = mapper.toFhir(src);

        assertThat(report.getIdElement().getIdPart()).isEqualTo("imgreport-" + src.getId());
        assertThat(report.getStatus()).isEqualTo(DiagnosticReport.DiagnosticReportStatus.APPENDED);
        assertThat(report.getCategory().get(0).getCoding().get(0).getCode()).isEqualTo("RAD");
        assertThat(report.getConclusion()).isEqualTo("No acute intracranial abnormality.");
        assertThat(report.getSubject().getReference())
            .isEqualTo("Patient/" + patient.getId());
    }

    @Test
    @DisplayName("an ERROR imaging report reads entered-in-error, not preliminary")
    void imagingErrorStatus() {
        ImagingReport src = new ImagingReport();
        src.setId(UUID.randomUUID());
        src.setReportStatus(ImagingReportStatus.ERROR);
        assertThat(mapper.toFhir(src).getStatus())
            .isEqualTo(DiagnosticReport.DiagnosticReportStatus.ENTEREDINERROR);
    }
}
