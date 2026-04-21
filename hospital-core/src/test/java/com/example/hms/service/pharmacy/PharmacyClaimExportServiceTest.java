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
}
