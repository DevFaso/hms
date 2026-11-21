package com.example.hms.mapper;

import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.payload.dto.imaging.ImagingReportResponseDTO;
import com.example.hms.service.support.PatientSafeView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2912 (r66): the signed report (screen and print) never showed who performed the exam, and
 * the dose the technician recorded did not travel with it. The exam record stays on the ORDER
 * (policy rule 3); a report's reader gets it beside the read, read from the order every time.
 * The server writes none of it into the report: its own contrast and dose fields are what its
 * author submitted (AlertNeverRecordsTreatmentGuardTest, CR-06 class).
 */
class ImagingReportMapperExamRecordTest {

    private final ImagingReportMapper mapper = new ImagingReportMapper();

    private static ImagingOrder examinedOrder() {
        ImagingOrder order = new ImagingOrder();
        order.setId(UUID.randomUUID());
        order.setPerformedByName("Test Manipulateur 3");
        order.setExamStartedAt(LocalDateTime.of(2026, 11, 20, 9, 30));
        order.setExamContrastAdministered(true);
        order.setExamContrastDetails("Iohexol 80 mL IV");
        order.setExamRadiationDoseMgy(new BigDecimal("0.15"));
        return order;
    }

    @Test
    void theExamRecordOfTheStudyIsShownBesideTheReport() {
        ImagingReport report = new ImagingReport();
        report.setImagingOrder(examinedOrder());

        ImagingReportResponseDTO dto = mapper.toResponseDTO(report);

        assertThat(dto.getExamPerformedByName()).isEqualTo("Test Manipulateur 3");
        assertThat(dto.getExamStartedAt()).isEqualTo(LocalDateTime.of(2026, 11, 20, 9, 30));
        assertThat(dto.getExamContrastAdministered()).isTrue();
        assertThat(dto.getExamContrastDetails()).isEqualTo("Iohexol 80 mL IV");
        assertThat(dto.getExamRadiationDoseMgy()).isEqualByComparingTo("0.15");
    }

    @Test
    void theReportsOwnContrastAndDoseStayWhatItsAuthorSubmitted() {
        ImagingReport report = new ImagingReport();
        report.setImagingOrder(examinedOrder());

        ImagingReportResponseDTO dto = mapper.toResponseDTO(report);

        // Nothing was submitted on the report: the exam record is displayed, never copied into it.
        assertThat(dto.getRadiationDoseMgy()).isNull();
        assertThat(dto.getContrastDetails()).isNull();
        assertThat(report.getRadiationDoseMgy()).isNull();
        assertThat(report.getContrastDetails()).isNull();
    }

    @Test
    void aReportWithNoOrderOrNoExamRecordCarriesNone() {
        ImagingReportResponseDTO orphan = mapper.toResponseDTO(new ImagingReport());
        assertThat(orphan.getExamPerformedByName()).isNull();
        assertThat(orphan.getExamRadiationDoseMgy()).isNull();

        ImagingReport report = new ImagingReport();
        report.setImagingOrder(new ImagingOrder());
        ImagingReportResponseDTO unrecorded = mapper.toResponseDTO(report);
        assertThat(unrecorded.getExamContrastAdministered()).isNull();
        assertThat(unrecorded.getExamRadiationDoseMgy()).isNull();
    }

    @Test
    void thePatientsCopyLeavesTheExamRecordOut() {
        ImagingReport report = new ImagingReport();
        report.setImagingOrder(examinedOrder());

        ImagingReportResponseDTO patientCopy = PatientSafeView.imagingReport(mapper.toResponseDTO(report));

        assertThat(patientCopy.getExamPerformedByName()).isNull();
        assertThat(patientCopy.getExamContrastDetails()).isNull();
        assertThat(patientCopy.getExamRadiationDoseMgy()).isNull();
    }
}
