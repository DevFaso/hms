package com.example.hms.security.chart;

import com.example.hms.exception.ChartRestrictedException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.BreakGlassSession;
import com.example.hms.model.Hospital;
import com.example.hms.repository.BreakGlassSessionRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.recordaccess.BreakGlassGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("D3863: the restricted-chart gate")
class RestrictedChartGateTest {

    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID HOSPITAL = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    private final PatientRepository patients = mock(PatientRepository.class);
    private final PatientHospitalRegistrationRepository registrations = mock(PatientHospitalRegistrationRepository.class);
    private final BreakGlassGate breakGlass = mock(BreakGlassGate.class);
    private final BreakGlassSessionRepository sessions = mock(BreakGlassSessionRepository.class);
    private RestrictedChartGate gate;

    @BeforeEach
    void setUp() {
        gate = new RestrictedChartGate(patients, registrations, breakGlass, sessions,
            Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneOffset.UTC));
        when(patients.findChartRestrictedById(PATIENT)).thenReturn(Optional.of(true));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
    }

    private static void signIn(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "user", "n/a", AuthorityUtils.createAuthorityList(roles)));
    }

    private static void actAt(UUID hospital) {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(ACTOR)
            .activeHospitalId(hospital)
            .permittedHospitalIds(Set.of(HOSPITAL))
            .staffHospitalIds(Set.of(HOSPITAL))
            .build());
    }

    @Test
    @DisplayName("doctors, nurses, midwives and administrators are subject; the patient, the desk and fulfilment are not")
    void whoIsSubject() {
        for (String role : List.of("ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_HOSPITAL_ADMIN",
                "ROLE_SUPER_ADMIN", "ROLE_ACCOUNTANT")) {
            signIn(role);
            assertThat(gate.isSubject()).as(role).isTrue();
        }
        for (String role : List.of("ROLE_PATIENT", "ROLE_RECEPTIONIST", "ROLE_PHARMACIST", "ROLE_LAB_SCIENTIST",
                "ROLE_RADIOLOGIST", "ROLE_IMAGING_TECHNICIAN")) {
            signIn(role);
            assertThat(gate.isSubject()).as(role).isFalse();
        }
        signIn("ROLE_RECEPTIONIST", "ROLE_NURSE");
        assertThat(gate.isSubject()).as("any subject role makes the caller subject").isTrue();
        SecurityContextHolder.clearContext();
        assertThat(gate.isSubject()).isFalse();
    }

    @Test
    @DisplayName("a restricted chart without a live session is blocked, and refused 403 where the patient is registered")
    void blockedWithoutASession() {
        signIn("ROLE_MIDWIFE");
        actAt(HOSPITAL);
        when(breakGlass.liveSession(ACTOR, PATIENT, HOSPITAL)).thenReturn(Optional.empty());
        when(registrations.existsByPatientIdAndHospitalId(PATIENT, HOSPITAL)).thenReturn(true);

        assertThat(gate.isBlocked(PATIENT)).isTrue();
        assertThatThrownBy(() -> gate.requireReadable(PATIENT)).isInstanceOf(ChartRestrictedException.class);
    }

    @Test
    @DisplayName("a stranger is answered 404, so the refusal does not say the chart exists")
    void strangerIsNotFound() {
        signIn("ROLE_DOCTOR");
        actAt(HOSPITAL);
        when(breakGlass.liveSession(ACTOR, PATIENT, HOSPITAL)).thenReturn(Optional.empty());
        when(registrations.existsByPatientIdAndHospitalId(PATIENT, HOSPITAL)).thenReturn(false);

        assertThatThrownBy(() -> gate.requireReadable(PATIENT)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a live break-the-glass session at the acting hospital opens the chart")
    void breakGlassOpensIt() {
        signIn("ROLE_DOCTOR");
        actAt(HOSPITAL);
        when(breakGlass.liveSession(ACTOR, PATIENT, HOSPITAL)).thenReturn(Optional.of(new BreakGlassSession()));

        assertThat(gate.isBlocked(PATIENT)).isFalse();
        assertThatCode(() -> gate.requireReadable(PATIENT)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("with no hospital pinned, a live session at a hospital where the caller works counts; elsewhere not")
    void unpinnedSession() {
        signIn("ROLE_DOCTOR");
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(ACTOR)
            .staffHospitalIds(Set.of(HOSPITAL))
            .build());
        Hospital here = new Hospital();
        here.setId(HOSPITAL);
        Hospital elsewhere = new Hospital();
        elsewhere.setId(UUID.randomUUID());
        BreakGlassSession atElsewhere = BreakGlassSession.builder().hospital(elsewhere).build();
        when(sessions.findLiveForUserAndPatient(eq(ACTOR), eq(PATIENT), any())).thenReturn(List.of(atElsewhere));
        assertThat(gate.isBlocked(PATIENT)).isTrue();

        BreakGlassSession atHere = BreakGlassSession.builder().hospital(here).build();
        when(sessions.findLiveForUserAndPatient(eq(ACTOR), eq(PATIENT), any())).thenReturn(List.of(atHere));
        assertThat(gate.isBlocked(PATIENT)).isFalse();
    }

    @Test
    @DisplayName("an open chart, or a caller outside the restriction, is never blocked and costs no session lookup")
    void openOrNotSubject() {
        UUID open = UUID.randomUUID();
        when(patients.findChartRestrictedById(open)).thenReturn(Optional.of(false));
        signIn("ROLE_DOCTOR");
        actAt(HOSPITAL);
        assertThat(gate.isBlocked(open)).isFalse();

        signIn("ROLE_RECEPTIONIST");
        assertThat(gate.isBlocked(PATIENT)).isFalse();
        verify(breakGlass, never()).liveSession(any(), any(), any());
    }

    @Test
    @DisplayName("several patients are looked up in one query")
    void blockedAmongIsOneQuery() {
        UUID open = UUID.randomUUID();
        signIn("ROLE_NURSE");
        actAt(HOSPITAL);
        when(patients.findChartRestrictedIdsAmong(any())).thenReturn(List.of(PATIENT));
        when(breakGlass.liveSession(ACTOR, PATIENT, HOSPITAL)).thenReturn(Optional.empty());

        assertThat(gate.blockedAmong(List.of(PATIENT, open))).containsExactly(PATIENT);
        verify(patients, never()).findChartRestrictedById(any());
    }

    @Test
    @DisplayName("a super-admin is held to the same rule and told the chart is restricted")
    void superAdmin() {
        signIn("ROLE_SUPER_ADMIN");
        HospitalContextHolder.setContext(HospitalContext.builder().principalUserId(ACTOR).superAdmin(true).build());
        when(sessions.findLiveForUserAndPatient(eq(ACTOR), eq(PATIENT), any())).thenReturn(List.of());

        assertThatThrownBy(() -> gate.requireReadable(PATIENT)).isInstanceOf(ChartRestrictedException.class);
    }
}
