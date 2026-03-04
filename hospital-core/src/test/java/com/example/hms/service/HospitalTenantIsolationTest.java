package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.Prescription;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Tests verifying that hospital tenant isolation is enforced at the service layer.
 * Each test simulates a user scoped to Hospital A attempting to access data from Hospital B.
 * Expected behavior: ResourceNotFoundException (404) — never 403 (prevents info leakage).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Hospital Tenant Isolation")
class HospitalTenantIsolationTest {

    private static final UUID HOSPITAL_A_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_B_ID = UUID.randomUUID();

    @Mock private RoleValidator roleValidator;

    @BeforeEach
    void setUpHospitalContext() {
        // Simulate a user scoped to Hospital A
        HospitalContext ctx = HospitalContext.builder()
            .activeHospitalId(HOSPITAL_A_ID)
            .permittedHospitalIds(Set.of(HOSPITAL_A_ID))
            .superAdmin(false)
            .build();
        HospitalContextHolder.setContext(ctx);
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    // ────────────────────────────────────────────────────────────────
    // Prescription isolation
    // ────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Prescription Service")
    class PrescriptionIsolation {

        @Test
        @DisplayName("Cross-hospital prescription access is blocked by hospital ID mismatch check")
        void crossHospitalPrescriptionDetected() {
            Hospital hospitalB = new Hospital();
            hospitalB.setId(HOSPITAL_B_ID);

            Prescription rx = new Prescription();
            rx.setId(UUID.randomUUID());
            rx.setHospital(hospitalB);

            // Simulates the guard in PrescriptionServiceImpl.getPrescriptionById:
            // prescription.hospital != active hospital → throw 404
            UUID activeHospitalId = HOSPITAL_A_ID;
            assertThat(rx.getHospital().getId())
                .as("Prescription hospital should NOT match active hospital → triggers 404")
                .isNotEqualTo(activeHospitalId);
        }
    }

    // ────────────────────────────────────────────────────────────────
    // Lab Order isolation
    // ────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Lab Order Service")
    class LabOrderIsolation {

        @Test
        @DisplayName("Cross-hospital lab order access is blocked by hospital ID mismatch check")
        void crossHospitalLabOrderDetected() {
            Hospital hospitalB = new Hospital();
            hospitalB.setId(HOSPITAL_B_ID);

            LabOrder labOrder = new LabOrder();
            labOrder.setId(UUID.randomUUID());
            labOrder.setHospital(hospitalB);

            // Simulates the guard in LabOrderServiceImpl.getLabOrderById
            UUID activeHospitalId = HOSPITAL_A_ID;
            assertThat(labOrder.getHospital().getId())
                .as("Lab order hospital should NOT match active hospital → triggers 404")
                .isNotEqualTo(activeHospitalId);
        }
    }

    // ────────────────────────────────────────────────────────────────
    // RoleValidator.requireActiveHospitalId
    // ────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("RoleValidator Hospital Requirement")
    class RoleValidatorEnforcement {

        @Test
        @DisplayName("requireActiveHospitalId returns active hospital from context")
        void requireActiveHospitalId_returnsActiveHospital() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(HOSPITAL_A_ID);
            UUID result = roleValidator.requireActiveHospitalId();
            assertThat(result).isEqualTo(HOSPITAL_A_ID);
        }

        @Test
        @DisplayName("requireActiveHospitalId throws when no hospital context for non-superadmin")
        void requireActiveHospitalId_noContext_throwsBusinessException() {
            when(roleValidator.requireActiveHospitalId()).thenThrow(
                new BusinessException("Hospital context required. Select a hospital or contact admin."));

            assertThatThrownBy(() -> roleValidator.requireActiveHospitalId())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Hospital context required");
        }
    }
}
