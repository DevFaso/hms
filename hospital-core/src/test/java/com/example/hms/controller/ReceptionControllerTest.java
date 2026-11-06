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

        Map<String, String> statusBody = Map.of("status", "IN_PROGRESS");

        assertThatThrownBy(() -> controller.updateEncounterStatus(
                ENCOUNTER_ID, statusBody, doctorPrincipal
            ))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("own patients");
    }

    @Test
    void updateEncounterStatus_encounterNotInHospital_propagatesResourceNotFoundException() {
        doThrow(new ResourceNotFoundException("Encounter not found"))
            .when(receptionService)
            .updateEncounterStatus(eq(ENCOUNTER_ID), any(), eq(HOSPITAL_ID), eq(RECEPTIONIST_USERNAME));

        Map<String, String> statusBody = Map.of("status", "ARRIVED");

        assertThatThrownBy(() -> controller.updateEncounterStatus(
                ENCOUNTER_ID, statusBody, receptionistPrincipal
            ))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("not found");
    }

    @Test
    void updateEncounterStatus_missingStatusField_returns400() {
        // QA round 29 sweep (assertion changed): still a 400, now named on "status" (it was an empty body).
        assertThatThrownBy(() -> controller.updateEncounterStatus(ENCOUNTER_ID, Map.of(), receptionistPrincipal))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                ex -> assertThat(ex.getFieldErrors()).containsKey("status"));
    }

    @Test
    void updateEncounterStatus_invalidStatusValue_returns400() {
        // QA round 29 sweep (assertion changed): still a 400, now named on "status" (it was an empty body).
        assertThatThrownBy(() -> controller.updateEncounterStatus(ENCOUNTER_ID, Map.of("status", "NOT_A_REAL_STATUS"), receptionistPrincipal))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                ex -> assertThat(ex.getFieldErrors()).containsKey("status"));
    }

    /* ═══════════════════════════════════════════════════════════════════
       GET /reception/patients/duplicate-candidates (QA D40)
       ═══════════════════════════════════════════════════════════════════ */

    @Test
    void duplicateCandidates_noPinnedHospital_returnsEmptyWithoutSearching() {
        HospitalContextHolder.clear();

        var response = controller.getDuplicateCandidates("Awa", null, null);

        org.assertj.core.api.Assertions.assertThat(response.getBody()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(receptionService);
    }

    /* ═══════════════════════════════════════════════════════════════════
       QA D1385 (round 32): GET /reception/queue and /reception/waitlist
       ═══════════════════════════════════════════════════════════════════ */

    @Test
    void d1385_anUnknownWaitlistStatusIsRefusedByName() {
        for (String bad : java.util.List.of("BOGUS", "En attente", "tous")) {
            assertThatThrownBy(() -> controller.getWaitlist(null, bad))
                .isInstanceOf(com.example.hms.exception.InvalidQueryParameterException.class)
                .extracting("parameter").isEqualTo("status");
        }
        org.mockito.Mockito.verifyNoInteractions(receptionService);
    }

    @Test
    void d1385_aWaitlistStatusIsReadInAnyCaseAndAllListsEveryEntry() {
        controller.getWaitlist(null, " waiting ");
        verify(receptionService).getWaitlist(HOSPITAL_ID, null, "WAITING");
        controller.getWaitlist(null, "all");
        verify(receptionService).getWaitlist(HOSPITAL_ID, null, null);
    }

    @Test
    void d1385_anUnknownQueueStatusIsRefusedByName() {
        assertThatThrownBy(() -> controller.getQueue(null, "BOGUS", null, null))
            .isInstanceOf(com.example.hms.exception.InvalidQueryParameterException.class)
            .extracting("parameter").isEqualTo("status");
        org.mockito.Mockito.verifyNoInteractions(receptionService);
    }

    @Test
    void d1385_aQueueStatusIsReadInAnyCase() {
        controller.getQueue(java.time.LocalDate.of(2026, 11, 3), "arrived", null, null);
        verify(receptionService).getQueue(java.time.LocalDate.of(2026, 11, 3), HOSPITAL_ID, "ARRIVED", null, null);
    }

    /**
     * QA D1385: the accepted queue and waitlist statuses are every status the
     * service can put on a row - a status missing here would be refused while
     * the service still shows it.
     */
    @Test
    void d1385_theAcceptedStatusesCoverEveryStatusTheServiceProduces() throws IllegalAccessException {
        for (java.lang.reflect.Field f : com.example.hms.service.ReceptionServiceImpl.class.getDeclaredFields()) {
            if (f.getType() != String.class || !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            if (f.getName().startsWith("WAITLIST_STATUS_")) {
                assertThat((String) f.get(null)).as(f.getName()).isIn(ReceptionController.WAITLIST_STATUSES);
            } else if (f.getName().startsWith("STATUS_")) {
                assertThat((String) f.get(null)).as(f.getName()).isIn(ReceptionController.QUEUE_STATUSES);
            }
        }
        for (com.example.hms.enums.AppointmentStatus value : com.example.hms.enums.AppointmentStatus.values()) {
            assertThat(value.name()).isIn(ReceptionController.QUEUE_STATUSES);
        }
    }
}
