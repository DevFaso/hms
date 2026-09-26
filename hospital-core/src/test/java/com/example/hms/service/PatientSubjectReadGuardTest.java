package com.example.hms.service;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.model.Patient;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link PatientSubjectReadGuard} with a real {@link ControllerAuthUtils}, so
 * the user id is resolved exactly as it is in production — including from the
 * {@code appUserId} claim of a Keycloak token.
 */
class PatientSubjectReadGuardTest {

    private static final java.util.Set<String> ROLES = PatientSubjectReaderRoles.CONSULTATIONS_BY_PATIENT;

    private final PatientRepository patientRepository = mock(PatientRepository.class);
    private final PatientSubjectReadGuard guard = new PatientSubjectReadGuard(
        new ControllerAuthUtils(mock(UserRoleHospitalAssignmentRepository.class)), patientRepository);

    private final UUID callerUserId = UUID.randomUUID();
    private final UUID ownPatientId = UUID.randomUUID();
    private final UUID otherPatientId = UUID.randomUUID();

    @BeforeEach
    void linkTheCallerToTheirRow() {
        when(patientRepository.existsByIdAndUserId(ownPatientId, callerUserId)).thenReturn(true);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void passwordLogin(String... roles) {
        var authorities = Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        var principal = new CustomUserDetails(callerUserId, "caller", "pw", true, authorities);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    private void keycloakLogin(String... roles) {
        var authorities = Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        Jwt jwt = Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .claim("sub", "keycloak-subject")
            .claim("appUserId", callerUserId.toString())
            .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
    }

    @Test
    @DisplayName("a patient reads their own rows")
    void patientReadsOwn() {
        passwordLogin("ROLE_PATIENT");
        assertThat(guard.mayRead(ROLES, ownPatientId)).isTrue();
    }

    @Test
    @DisplayName("a patient is refused another patient's rows")
    void patientRefusedAnothers() {
        passwordLogin("ROLE_PATIENT");
        assertThat(guard.mayRead(ROLES, otherPatientId)).isFalse();
    }

    @Test
    @DisplayName("a Keycloak patient is resolved from appUserId, not the subject: own yes, another's no")
    void keycloakPatientResolvedFromAppUserId() {
        keycloakLogin("ROLE_PATIENT");
        assertThat(guard.mayRead(ROLES, ownPatientId)).isTrue();
        assertThat(guard.mayRead(ROLES, otherPatientId)).isFalse();
    }

    @Test
    @DisplayName("a row whose patient cannot be placed is never a patient's")
    void nullSubjectIsNeverOwned() {
        passwordLogin("ROLE_PATIENT");
        assertThat(guard.mayRead(ROLES, (UUID) null)).isFalse();
        verify(patientRepository, never()).existsByIdAndUserId(any(), any());
    }

    @Test
    @DisplayName("a patient principal with no resolvable user id owns nothing")
    void unresolvableCallerOwnsNothing() {
        var authorities = java.util.List.of(new SimpleGrantedAuthority("ROLE_PATIENT"));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("someone", null, authorities));
        assertThat(guard.mayRead(ROLES, ownPatientId)).isFalse();
    }

    @Test
    @DisplayName("staff who are also patients keep their staff access, without an ownership lookup")
    void staffWhoAreAlsoPatientsKeepStaffAccess() {
        passwordLogin("ROLE_PATIENT", "ROLE_NURSE");
        assertThat(guard.mayRead(ROLES, otherPatientId)).isTrue();
        verify(patientRepository, never()).existsByIdAndUserId(any(), any());
    }

    @Test
    @DisplayName("an expanded super-admin, who holds ROLE_PATIENT by inheritance, is not a patient here")
    void expandedSuperAdminIsNotAPatient() {
        passwordLogin("ROLE_SUPER_ADMIN", "ROLE_PATIENT", "ROLE_DOCTOR");
        assertThat(guard.mayRead(ROLES, otherPatientId)).isTrue();
    }

    @Test
    @DisplayName("a role the endpoint does not admit does not lift subject status")
    void unadmittedRoleDoesNotLiftSubjectStatus() {
        // ROLE_SURGEON over Keycloak (no RoleExpansion) enters a consultation
        // read only through ROLE_PATIENT, so it is held to its own rows.
        keycloakLogin("ROLE_PATIENT", "ROLE_SURGEON");
        assertThat(guard.mayRead(ROLES, otherPatientId)).isFalse();
        assertThat(guard.mayRead(ROLES, ownPatientId)).isTrue();
    }

    @Test
    @DisplayName("no authentication is not a patient: internal callers are unaffected")
    void noAuthenticationIsNotAPatient() {
        assertThat(guard.mayRead(ROLES, otherPatientId)).isTrue();
        assertThat(guard.isPatientOnly(ROLES)).isFalse();
    }

    @Test
    @DisplayName("isPatientOnly answers against the endpoint's own set")
    void isPatientOnlyUsesTheEndpointSet() {
        passwordLogin("ROLE_PATIENT", "ROLE_RECEPTIONIST");
        // The front desk reads appointments, so there it is staff...
        assertThat(guard.isPatientOnly(PatientSubjectReaderRoles.APPOINTMENT_READS)).isFalse();
        // ...but a consultation read does not admit it: there it is a patient.
        assertThat(guard.isPatientOnly(PatientSubjectReaderRoles.CONSULTATIONS_BY_PATIENT)).isTrue();
    }

    @Test
    @DisplayName("the Patient overload: own yes, another's no, null never")
    void patientOverload() {
        passwordLogin("ROLE_PATIENT");
        Patient own = new Patient();
        own.setId(ownPatientId);
        Patient other = new Patient();
        other.setId(otherPatientId);

        assertThat(guard.mayRead(ROLES, own)).isTrue();
        assertThat(guard.mayRead(ROLES, other)).isFalse();
        assertThat(guard.mayRead(ROLES, (Patient) null)).isFalse();
        assertThat(guard.callerOwns(null)).isFalse();
    }

    @Test
    @DisplayName("for staff the Patient overload never touches the subject, so no lazy proxy is initialised")
    void staffNeverTouchTheSubject() {
        passwordLogin("ROLE_DOCTOR");
        Patient subject = mock(Patient.class);

        assertThat(guard.mayRead(ROLES, subject)).isTrue();
        verifyNoInteractions(subject);
        verify(patientRepository, never()).existsByIdAndUserId(any(), any());
    }
}
