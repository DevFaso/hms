package com.example.hms.service.pharmacy;

import com.example.hms.enums.PharmacyClaimStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.PharmacyClaim;
import com.example.hms.repository.pharmacy.PharmacyClaimRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * T-52: export service tests — CSV formatting & minimal FHIR Claim bundle.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PharmacyClaimExportService")
class PharmacyClaimExportServiceTest {

    @Mock private PharmacyClaimRepository claimRepository;
    @Mock private RoleValidator roleValidator;
    @InjectMocks private PharmacyClaimExportService service;

    private UUID hospitalId;
    private PharmacyClaim claim;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        Hospital h = new Hospital();
        h.setId(hospitalId);
        Patient p = new Patient();
        p.setId(UUID.randomUUID());
        Dispense d = new Dispense();
        d.setId(UUID.randomUUID());
        claim = PharmacyClaim.builder()
                .dispense(d)
                .patient(p)
                .hospital(h)
                .amount(new BigDecimal("2500"))
                .currency("XOF")
                .claimStatus(PharmacyClaimStatus.SUBMITTED)
                .notes("Prescription #12, comma in text")
                .build();
        claim.setId(UUID.randomUUID());
    }

    @Test
    @DisplayName("CSV export: includes header + one row per claim and escapes commas")
    void exportCsv() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findByHospitalIdAndClaimStatusIn(hospitalId,
                List.of(PharmacyClaimStatus.SUBMITTED)))
                .thenReturn(List.of(claim));

        String csv = new String(service.exportCsv(List.of(PharmacyClaimStatus.SUBMITTED)),
                StandardCharsets.UTF_8);

        assertThat(csv).startsWith("id,dispense_id,patient_id,hospital_id,");
        assertThat(csv).contains("2500,XOF");
        // The note contains a comma so must be quoted.
        assertThat(csv).contains("\"Prescription #12, comma in text\"");
    }

    @Test
    @DisplayName("FHIR export: produces a well-formed Claim bundle with currency + reference")
    void exportFhir() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findByHospitalIdAndClaimStatusIn(hospitalId,
                List.of(PharmacyClaimStatus.SUBMITTED)))
                .thenReturn(List.of(claim));

        String json = new String(service.exportFhirBundle(List.of(PharmacyClaimStatus.SUBMITTED)),
                StandardCharsets.UTF_8);

        assertThat(json).startsWith("{\"resourceType\":\"Bundle\"");
        assertThat(json).contains("\"resourceType\":\"Claim\"");
        assertThat(json).contains("\"status\":\"active\"");
        assertThat(json).contains("\"currency\":\"XOF\"");
        assertThat(json).contains("\"value\":2500");
    }

    @Test
    @DisplayName("Rejects export when no statuses requested")
    void rejectsEmptyStatusList() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        assertThatThrownBy(() -> service.exportCsv(List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("status");
    }

    @Test
    @DisplayName("Rejects export when statuses list is null")
    void rejectsNullStatusList() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        assertThatThrownBy(() -> service.exportCsv(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("status");
    }

    @Test
    @DisplayName("CSV export: emits header only when no claims are returned")
    void csvHeaderOnlyWhenEmpty() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findByHospitalIdAndClaimStatusIn(hospitalId,
                List.of(PharmacyClaimStatus.DRAFT))).thenReturn(List.of());

        String csv = new String(service.exportCsv(List.of(PharmacyClaimStatus.DRAFT)),
                StandardCharsets.UTF_8);

        assertThat(csv).startsWith("id,dispense_id,patient_id,hospital_id,");
        // Only the header row, nothing else.
        assertThat(csv.split("\n")).hasSize(1);
    }

    @Test
    @DisplayName("CSV export: handles null dispense, patient, hospital, amount, currency, submittedAt")
    void csvHandlesNullFields() {
        PharmacyClaim bare = PharmacyClaim.builder()
                .claimStatus(PharmacyClaimStatus.DRAFT)
                .build();
        bare.setId(UUID.randomUUID());
        // Ensure all nullable fields are explicitly null.
        bare.setDispense(null);
        bare.setPatient(null);
        bare.setHospital(null);
        bare.setAmount(null);
        bare.setCurrency(null);
        bare.setSubmittedAt(null);
        bare.setNotes(null);
        bare.setCoverageReference(null);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findByHospitalIdAndClaimStatusIn(hospitalId,
                List.of(PharmacyClaimStatus.DRAFT))).thenReturn(List.of(bare));

        String csv = new String(service.exportCsv(List.of(PharmacyClaimStatus.DRAFT)),
                StandardCharsets.UTF_8);

        // Should not throw, and should contain the claim id plus many empty fields.
        assertThat(csv).contains(bare.getId().toString());
        assertThat(csv).contains(",,,,");
    }

    @Test
    @DisplayName("CSV export: escapes embedded double-quotes in text fields")
    void csvEscapesEmbeddedQuotes() {
        claim.setNotes("contains \"quoted\" text");
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findByHospitalIdAndClaimStatusIn(hospitalId,
                List.of(PharmacyClaimStatus.SUBMITTED))).thenReturn(List.of(claim));

        String csv = new String(service.exportCsv(List.of(PharmacyClaimStatus.SUBMITTED)),
                StandardCharsets.UTF_8);

        // Double-quote escaping doubles up internal quotes inside the quoted field.
        assertThat(csv).contains("\"contains \"\"quoted\"\" text\"");
    }

    @Test
    @DisplayName("CSV export: emits formatted submittedAt when present")
    void csvIncludesSubmittedAt() {
        claim.setSubmittedAt(java.time.LocalDateTime.of(2026, 4, 1, 10, 0));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findByHospitalIdAndClaimStatusIn(hospitalId,
                List.of(PharmacyClaimStatus.SUBMITTED))).thenReturn(List.of(claim));

        String csv = new String(service.exportCsv(List.of(PharmacyClaimStatus.SUBMITTED)),
                StandardCharsets.UTF_8);

        assertThat(csv).contains("2026-04-01T10:00");
    }

    @Test
    @DisplayName("FHIR export: empty bundle when no claims match")
    void fhirBundleEmpty() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findByHospitalIdAndClaimStatusIn(hospitalId,
                List.of(PharmacyClaimStatus.DRAFT))).thenReturn(List.of());

        String json = new String(service.exportFhirBundle(List.of(PharmacyClaimStatus.DRAFT)),
                StandardCharsets.UTF_8);

        assertThat(json).isEqualTo("{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[]}");
    }

    @Test
    @DisplayName("FHIR export: maps DRAFT -> draft, REJECTED -> cancelled")
    void fhirMapsStatuses() {
        PharmacyClaim draft = PharmacyClaim.builder()
                .claimStatus(PharmacyClaimStatus.DRAFT)
                .currency("XOF")
                .amount(new BigDecimal("10"))
                .build();
        draft.setId(UUID.randomUUID());
        PharmacyClaim rejected = PharmacyClaim.builder()
                .claimStatus(PharmacyClaimStatus.REJECTED)
                .currency("XOF")
                .amount(new BigDecimal("20"))
                .build();
        rejected.setId(UUID.randomUUID());
        PharmacyClaim paid = PharmacyClaim.builder()
                .claimStatus(PharmacyClaimStatus.PAID)
                .currency("XOF")
                .amount(new BigDecimal("30"))
                .build();
        paid.setId(UUID.randomUUID());

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findByHospitalIdAndClaimStatusIn(
                any(), any()))
                .thenReturn(List.of(draft, rejected, paid));

        String json = new String(service.exportFhirBundle(
                List.of(PharmacyClaimStatus.DRAFT,
                        PharmacyClaimStatus.REJECTED,
                        PharmacyClaimStatus.PAID)),
                StandardCharsets.UTF_8);

        assertThat(json).contains("\"status\":\"draft\"");
        assertThat(json).contains("\"status\":\"cancelled\"");
        assertThat(json).contains("\"status\":\"active\"");
        // Multiple entries separated by comma
        assertThat(json.split("\"resourceType\":\"Claim\"")).hasSizeGreaterThan(2);
    }

    @Test
    @DisplayName("FHIR export: defaults null status to 'draft', null amount to 0, null currency to XOF")
    void fhirDefaultsForNullFields() {
        PharmacyClaim bare = PharmacyClaim.builder()
                .claimStatus(null)
                .amount(null)
                .currency(null)
                .build();
        bare.setId(UUID.randomUUID());
        bare.setPatient(null);
        bare.setHospital(null);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findByHospitalIdAndClaimStatusIn(any(), any()))
                .thenReturn(List.of(bare));

        String json = new String(service.exportFhirBundle(List.of(PharmacyClaimStatus.DRAFT)),
                StandardCharsets.UTF_8);

        assertThat(json).contains("\"status\":\"draft\"");
        assertThat(json).contains("\"value\":0");
        assertThat(json).contains("\"currency\":\"XOF\"");
        // References still emitted (with empty ids)
        assertThat(json).contains("\"Patient/\"");
        assertThat(json).contains("\"Organization/\"");
    }

    @Test
    @DisplayName("FHIR export: includes createdAt when present")
    void fhirIncludesCreatedAt() {
        claim.setCreatedAt(java.time.LocalDateTime.of(2026, 4, 1, 9, 30));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findByHospitalIdAndClaimStatusIn(any(), any()))
                .thenReturn(List.of(claim));

        String json = new String(service.exportFhirBundle(List.of(PharmacyClaimStatus.SUBMITTED)),
                StandardCharsets.UTF_8);

        assertThat(json).contains("2026-04-01T09:30");
    }
}
