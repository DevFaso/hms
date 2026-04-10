package com.example.hms.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.ReceptionService;
import com.example.hms.utility.RoleValidator;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

@ExtendWith(MockitoExtension.class)
class ReceptionControllerTest {

    @Mock private ReceptionService receptionService;
    @Mock private RoleValidator roleValidator;

    private ReceptionController controller;

    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private static final UUID ENCOUNTER_ID = UUID.randomUUID();
    private static final String RECEPTIONIST_USERNAME = "receptionist1";
    private static final String DOCTOR_USERNAME = "doctor1";

    private UserDetails receptionistPrincipal;
    private UserDetails doctorPrincipal;

    @BeforeEach
    void setUp() {
        controller = new ReceptionController(receptionService, roleValidator);

        // Seed the thread-local hospital context
        HospitalContextHolder.setContext(
            HospitalContext.builder()
                .activeHospitalId(HOSPITAL_ID)
                .build()
        );

        receptionistPrincipal = User.withUsername(RECEPTIONIST_USERNAME)
            .password("n/a")
            .authorities("ROLE_RECEPTIONIST")
            .build();

        doctorPrincipal = User.withUsername(DOCTOR_USERNAME)
            .password("n/a")
            .authorities("ROLE_DOCTOR")
            .build();
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
    }

    /* ═══════════════════════════════════════════════════════════════════
       PATCH /reception/encounters/{encounterId}/status
       ═══════════════════════════════════════════════════════════════════ */

    @Test
    void updateEncounterStatus_receptionist_returns204() {
        doNothing().when(receptionService)
            .updateEncounterStatus(ENCOUNTER_ID, EncounterStatus.IN_PROGRESS, HOSPITAL_ID, RECEPTIONIST_USERNAME);

        ResponseEntity<Void> response = controller.updateEncounterStatus(
            ENCOUNTER_ID,
            Map.of("status", "IN_PROGRESS"),
            receptionistPrincipal
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(receptionService).updateEncounterStatus(
            ENCOUNTER_ID, EncounterStatus.IN_PROGRESS, HOSPITAL_ID, RECEPTIONIST_USERNAME);
    }

    @Test
    void updateEncounterStatus_doctorNotAssigned_propagatesAccessDeniedException() {
        doThrow(new AccessDeniedException("You may only update encounter status for your own patients."))
            .when(receptionService)
            .updateEncounterStatus(eq(ENCOUNTER_ID), any(), eq(HOSPITAL_ID), eq(DOCTOR_USERNAME));

        assertThatThrownBy(() -> controller.updateEncounterStatus(
                ENCOUNTER_ID,
                Map.of("status", "IN_PROGRESS"),
                doctorPrincipal
            ))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("own patients");
    }

    @Test
    void updateEncounterStatus_encounterNotInHospital_propagatesResourceNotFoundException() {
        doThrow(new ResourceNotFoundException("Encounter not found"))
            .when(receptionService)
            .updateEncounterStatus(eq(ENCOUNTER_ID), any(), eq(HOSPITAL_ID), eq(RECEPTIONIST_USERNAME));

        assertThatThrownBy(() -> controller.updateEncounterStatus(
                ENCOUNTER_ID,
                Map.of("status", "ARRIVED"),
                receptionistPrincipal
            ))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("not found");
    }

    @Test
    void updateEncounterStatus_missingStatusField_returns400() {
        ResponseEntity<Void> response = controller.updateEncounterStatus(
            ENCOUNTER_ID,
            Map.of(),
            receptionistPrincipal
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateEncounterStatus_invalidStatusValue_returns400() {
        ResponseEntity<Void> response = controller.updateEncounterStatus(
            ENCOUNTER_ID,
            Map.of("status", "NOT_A_REAL_STATUS"),
            receptionistPrincipal
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
