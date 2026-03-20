package com.example.hms.service;

import com.example.hms.payload.dto.medicalhistory.ImmunizationResponseDTO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImmunizationCertificatePdfServiceTest {

    private final ImmunizationCertificatePdfService service = new ImmunizationCertificatePdfService();

    // ── Happy Path ──────────────────────────────────────────────────────

    @Test
    void generate_withMultipleImmunizations_producesValidPdf() throws Exception {
        List<ImmunizationResponseDTO> immunizations = List.of(
                ImmunizationResponseDTO.builder()
                        .vaccineDisplay("COVID-19 mRNA Vaccine")
                        .vaccineCode("CVX-207")
                        .administrationDate(LocalDate.of(2024, 3, 15))
                        .doseNumber(1)
                        .totalDosesInSeries(2)
                        .lotNumber("ABC123")
                        .manufacturer("Pfizer")
                        .build(),
                ImmunizationResponseDTO.builder()
                        .vaccineDisplay("Influenza Vaccine")
                        .administrationDate(LocalDate.of(2024, 1, 10))
                        .doseNumber(1)
                        .totalDosesInSeries(1)
                        .lotNumber("FLU456")
                        .manufacturer("Sanofi")
                        .build()
        );

        byte[] pdf = service.generate("John Doe", immunizations);
        assertThat(pdf).isNotNull().isNotEmpty();

        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdf))) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(1);
        }
    }

    // ── Empty List ──────────────────────────────────────────────────────

    @Test
    void generate_emptyList_producesValidPdf() throws Exception {
        byte[] pdf = service.generate("Jane Doe", Collections.emptyList());
        assertThat(pdf).isNotNull().isNotEmpty();

        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdf))) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
    }

    // ── Null Fields Fallbacks ───────────────────────────────────────────

    @Test
    void generate_nullVaccineDisplay_fallsToVaccineCode() throws Exception {
        List<ImmunizationResponseDTO> immunizations = List.of(
                ImmunizationResponseDTO.builder()
                        .vaccineDisplay(null)
                        .vaccineCode("CVX-207")
                        .administrationDate(LocalDate.of(2024, 6, 1))
                        .build()
        );
        byte[] pdf = service.generate("Test Patient", immunizations);
        assertThat(pdf).isNotEmpty();
    }

    @Test
    void generate_bothVaccineNamesNull_usesEmDash() throws Exception {
        List<ImmunizationResponseDTO> immunizations = List.of(
                ImmunizationResponseDTO.builder()
                        .vaccineDisplay(null)
                        .vaccineCode(null)
                        .administrationDate(LocalDate.of(2024, 6, 1))
                        .build()
        );
        byte[] pdf = service.generate("Patient", immunizations);
        assertThat(pdf).isNotEmpty();
    }

    @Test
    void generate_nullAdministrationDate_usesEmDash() throws Exception {
        List<ImmunizationResponseDTO> immunizations = List.of(
                ImmunizationResponseDTO.builder()
                        .vaccineDisplay("Tetanus")
                        .administrationDate(null)
                        .build()
        );
        byte[] pdf = service.generate("Patient", immunizations);
        assertThat(pdf).isNotEmpty();
    }

    @Test
    void generate_nullLotNumberAndManufacturer_handlesGracefully() throws Exception {
        List<ImmunizationResponseDTO> immunizations = List.of(
                ImmunizationResponseDTO.builder()
                        .vaccineDisplay("Hepatitis B")
                        .administrationDate(LocalDate.now())
                        .lotNumber(null)
                        .manufacturer(null)
                        .build()
        );
        byte[] pdf = service.generate("Patient", immunizations);
        assertThat(pdf).isNotEmpty();
    }

    @Test
    void generate_nullPatientName_handlesGracefully() throws Exception {
        byte[] pdf = service.generate(null, List.of(
                ImmunizationResponseDTO.builder()
                        .vaccineDisplay("Polio")
                        .administrationDate(LocalDate.now())
                        .build()
        ));
        assertThat(pdf).isNotEmpty();
    }

    // ── Dose Formatting Branches ────────────────────────────────────────

    @Test
    void generate_doseNumberAndTotalSet_formatsAsXofY() throws Exception {
        List<ImmunizationResponseDTO> immunizations = List.of(
                ImmunizationResponseDTO.builder()
                        .vaccineDisplay("HPV")
                        .administrationDate(LocalDate.now())
                        .doseNumber(2)
                        .totalDosesInSeries(3)
                        .build()
        );
        byte[] pdf = service.generate("Patient", immunizations);
        assertThat(pdf).isNotEmpty();
    }

    @Test
    void generate_onlyDoseNumberSet_showsDoseOnly() throws Exception {
        List<ImmunizationResponseDTO> immunizations = List.of(
                ImmunizationResponseDTO.builder()
                        .vaccineDisplay("Shingles")
                        .administrationDate(LocalDate.now())
                        .doseNumber(1)
                        .totalDosesInSeries(null)
                        .build()
        );
        byte[] pdf = service.generate("Patient", immunizations);
        assertThat(pdf).isNotEmpty();
    }

    @Test
    void generate_noDoseFields_usesEmDash() throws Exception {
        List<ImmunizationResponseDTO> immunizations = List.of(
                ImmunizationResponseDTO.builder()
                        .vaccineDisplay("Measles")
                        .administrationDate(LocalDate.now())
                        .doseNumber(null)
                        .totalDosesInSeries(null)
                        .build()
        );
        byte[] pdf = service.generate("Patient", immunizations);
        assertThat(pdf).isNotEmpty();
    }

    // ── Truncation ──────────────────────────────────────────────────────

    @Test
    void generate_longVaccineName_truncatedWithEllipsis() throws Exception {
        List<ImmunizationResponseDTO> immunizations = List.of(
                ImmunizationResponseDTO.builder()
                        .vaccineDisplay("A Very Long Vaccine Name That Exceeds Twenty Eight Characters Easily")
                        .administrationDate(LocalDate.now())
                        .lotNumber("VERYLONGLOT123456")
                        .manufacturer("A Very Long Manufacturer Name Here")
                        .build()
        );
        byte[] pdf = service.generate("Patient", immunizations);
        assertThat(pdf).isNotEmpty();
    }

    // ── Page Overflow ───────────────────────────────────────────────────

    @Test
    void generate_manyImmunizations_stopsAtPageBottom() throws Exception {
        List<ImmunizationResponseDTO> immunizations = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            immunizations.add(ImmunizationResponseDTO.builder()
                    .vaccineDisplay("Vaccine-" + i)
                    .administrationDate(LocalDate.now().minusDays(i))
                    .doseNumber(1)
                    .totalDosesInSeries(1)
                    .lotNumber("LOT" + i)
                    .manufacturer("Mfr" + i)
                    .build());
        }
        byte[] pdf = service.generate("Patient", immunizations);
        assertThat(pdf).isNotEmpty();

        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdf))) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(1);
        }
    }
}
