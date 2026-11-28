package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.service.escalation.UrgentAlertOverviewService;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Review of #1393 (F2): the urgent-alert reads take the caller's roles AT the acting hospital. One account that is
 * an administrator at hospital B and a nurse at hospital A, acting at A, is a nurse there: she reads her own alerts
 * and the patients' marks of A, and not A's register.
 */
class UrgentAlertControllerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID HOSPITAL_A = UUID.randomUUID();
    private static final UUID HOSPITAL_B = UUID.randomUUID();

    private final UrgentAlertOverviewService overview = mock(UrgentAlertOverviewService.class);
    private final ControllerAuthUtils authUtils = mock(ControllerAuthUtils.class);
    private final RoleValidator roles = mock(RoleValidator.class);
    private final Authentication auth = mock(Authentication.class);
    private UrgentAlertController controller;

    @BeforeEach
    void setUp() {
        controller = new UrgentAlertController(overview, authUtils, roles);
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(USER));
        when(authUtils.resolveHospitalScope(eq(auth), isNull(), isNull(), eq(false))).thenReturn(HOSPITAL_A);
        // The token carries the union of both assignments: an administrator's authority is in it.
        when(authUtils.hasAuthority(any(), any())).thenReturn(true);
        when(roles.isHospitalAdmin(USER, HOSPITAL_B)).thenReturn(true);
        when(roles.isNurse(USER, HOSPITAL_A)).thenReturn(true);
        when(overview.patientMarks(HOSPITAL_A)).thenReturn(List.of());
    }

    @Test
    void anAdministratorElsewhereIsNotAnAdministratorOfTheHospitalSheActsFor() {
        controller.incidents(auth);

        // Neither administrator nor doctor at A: the service refuses her, as it refuses any other reader.
        verify(overview).incidents(USER, HOSPITAL_A, false, false);
    }

    @Test
    void theNurseReadsHerOwnAlertsAndThePatientsMarksAtTheHospitalWhereSheIsANurse() {
        controller.mine(auth);
        controller.patients(auth);

        verify(overview).mine(USER, HOSPITAL_A);
        verify(overview).patientMarks(HOSPITAL_A);
    }

    @Test
    void aClinicianElsewhereReadsNoAlertOfTheHospitalSheActsFor() {
        // Administrator at A, nurse at B, acting at A.
        when(roles.isNurse(USER, HOSPITAL_A)).thenReturn(false);
        when(roles.isNurse(USER, HOSPITAL_B)).thenReturn(true);
        when(roles.isHospitalAdmin(USER, HOSPITAL_A)).thenReturn(true);

        assertThatThrownBy(() -> controller.mine(auth)).isInstanceOf(ClientSafeAccessDeniedException.class);
        assertThatThrownBy(() -> controller.patients(auth)).isInstanceOf(ClientSafeAccessDeniedException.class);
        verify(overview, never()).mine(any(), any());
        verify(overview, never()).patientMarks(any());
        // She is the administrator of A: the register is hers, without the patient.
        controller.incidents(auth);
        verify(overview).incidents(eq(USER), eq(HOSPITAL_A), eq(true), anyBoolean());
    }
}
