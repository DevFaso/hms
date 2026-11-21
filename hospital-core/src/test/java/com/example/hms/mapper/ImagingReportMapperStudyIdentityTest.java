package com.example.hms.mapper;

import com.example.hms.enums.ImagingModality;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.payload.dto.imaging.ImagingReportResponseDTO;
import com.example.hms.payload.dto.imaging.ImagingReportUpsertRequestDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D3000 (r67): the signed report showed « Modalité » empty and « Région du
 * corps — » although its order was a Radiographie / Thorax.
 */
class ImagingReportMapperStudyIdentityTest {

    private final ImagingReportMapper mapper = new ImagingReportMapper();

    private static ImagingOrder thoraxXray() {
        ImagingOrder order = new ImagingOrder();
        order.setId(UUID.randomUUID());
        order.setModality(ImagingModality.XRAY);
        order.setBodyRegion("Thorax");
        return order;
    }

    @Test
    @DisplayName("a stored report with no modality or region reads its order's")
    void storedRowFallsBackToTheOrder() {
        ImagingReport report = new ImagingReport();
        report.setImagingOrder(thoraxXray());
        report.setBodyRegion("  ");

        ImagingReportResponseDTO dto = mapper.toResponseDTO(report);

        assertThat(dto.getModality()).isEqualTo(ImagingModality.XRAY);
        assertThat(dto.getBodyRegion()).isEqualTo("Thorax");
    }

    @Test
    @DisplayName("what the author typed is kept")
    void authorsValuesWin() {
        ImagingReport report = new ImagingReport();
        report.setImagingOrder(thoraxXray());
        report.setModality(ImagingModality.CT);
        report.setBodyRegion("Thorax et abdomen");

        ImagingReportResponseDTO dto = mapper.toResponseDTO(report);

        assertThat(dto.getModality()).isEqualTo(ImagingModality.CT);
        assertThat(dto.getBodyRegion()).isEqualTo("Thorax et abdomen");
    }

    @Test
    @DisplayName("a report saved blank stores its order's modality and region, so the signature attests to them")
    void savedBlankInheritsTheOrder() {
        ImagingReport report = new ImagingReport();
        report.setImagingOrder(thoraxXray());
        ImagingReportUpsertRequestDTO request = new ImagingReportUpsertRequestDTO();
        request.setFindings("Pas d'anomalie.");

        mapper.updateReportFromRequest(report, request);

        assertThat(report.getModality()).isEqualTo(ImagingModality.XRAY);
        assertThat(report.getBodyRegion()).isEqualTo("Thorax");
    }
}
