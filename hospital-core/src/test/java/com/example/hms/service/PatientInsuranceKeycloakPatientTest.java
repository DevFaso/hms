package com.example.hms.service;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.ActingMode;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PatientInsuranceMapper;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientInsurance;
import com.example.hms.payload.dto.LinkPatientInsuranceRequestDTO;
import com.example.hms.payload.dto.PatientInsuranceResponseDTO;
import com.example.hms.repository.PatientInsuranceRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.ActingContext;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PatientInsurance for a patient signed in through Keycloak.
 * {@code RoleValidator.getCurrentUserId()} (and {@code ActingContext.userId})
 * are null on a {@code JwtAuthenticationToken}, so the self-access check failed
 * closed and refused the patient their own insurance. The service now asks the
 * shared {@link PatientSubjectReadGuard}, which reads the {@code appUserId}
 * claim — run here for real, over a real {@link ControllerAuthUtils}, with a
 * token whose subject is NOT the user id.
 */
@DisplayName("PatientInsurance over a Keycloak token")
class PatientInsuranceKeycloakPatientTest {

    private final UUID callerUserId = UUID.randomUUID();
    private final UUID ownPatientId = UUID.randomUUID();
    private final UUID otherPatientId = UUID.randomUUID();
    private final UUID unknownPatientId = UUID.randomUUID();

    private final PatientInsuranceRepository insuranceRepository = mock(PatientInsuranceRepository.class);
    private final PatientRepository patientRepository = mock(PatientRepository.class);
    private final PatientInsuranceMapper mapper = mock(PatientInsuranceMapper.class);
    private final MessageSource messageSource = mock(MessageSource.class);
    private final RoleValidator roleValidator = mock(RoleValidator.class);
    private final PatientChartAccess chartAccess = mock(PatientChartAccess.class);

    private PatientInsuranceServiceImpl service;

    @BeforeEach
    void setUp() {
        PatientSubjectReadGuard guard = new PatientSubjectReadGuard(
            new ControllerAuthUtils(mock(UserRoleHospitalAssignmentRepository.class)), patientRepository);
        service = new PatientInsuranceServiceImpl(insuranceRepository, patientRepository,
            mock(UserRoleHospitalAssignmentRepository.class), mapper, messageSource, roleValidator, chartAccess, guard);

        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256")
            .claim("sub", "keycloak-subject").claim("appUserId", callerUserId.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_PATIENT"))));

        when(roleValidator.isPatientOnlyFromAuth()).thenReturn(true);
        // The resolver PR 1 owns still answers null on this path; the service must not depend on it.
        when(roleValidator.getCurrentUserId()).thenReturn(null);
        when(patientRepository.existsByIdAndUserId(ownPatientId, callerUserId)).thenReturn(true);
        when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
            .thenAnswer(inv -> inv.getArgument(0) + ":" + java.util.Arrays.toString((Object[]) inv.getArgument(1)));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static Patient patient(UUID id) {
        Patient p = Patient.builder().build();
        p.setId(id);
        return p;
    }

    @Test
    @DisplayName("the patient lists their own insurance; another's id answers as an unknown one, before the chart lookup")
    void listsOwnOnly() {
        when(insuranceRepository.findByPatient_Id(ownPatientId)).thenReturn(List.of(new PatientInsurance()));

        assertThat(service.getInsurancesByPatientId(ownPatientId, Locale.ENGLISH)).hasSize(1);

        ResourceNotFoundException foreign = catchThrowableOfType(ResourceNotFoundException.class,
            () -> service.getInsurancesByPatientId(otherPatientId, Locale.ENGLISH));
        ResourceNotFoundException unknown = catchThrowableOfType(ResourceNotFoundException.class,
            () -> service.getInsurancesByPatientId(unknownPatientId, Locale.ENGLISH));
        assertThat(foreign.getMessageKey()).isEqualTo(unknown.getMessageKey()).isEqualTo("patient.notFound");
        verify(chartAccess, never()).require(otherPatientId, null);
        verify(insuranceRepository, never()).findByPatient_Id(otherPatientId);
    }

    @Test
    @DisplayName("the patient reads their own insurance by id; another patient's answers as a missing id")
    void readsOwnById() {
        UUID insuranceId = UUID.randomUUID();
        PatientInsurance own = new PatientInsurance();
        own.setPatient(patient(ownPatientId));
        PatientInsuranceResponseDTO dto = new PatientInsuranceResponseDTO();
        when(insuranceRepository.findById(insuranceId)).thenReturn(Optional.of(own));
        when(mapper.toPatientInsuranceResponseDTO(own)).thenReturn(dto);

        assertThat(service.getPatientInsuranceById(insuranceId, Locale.ENGLISH)).isSameAs(dto);

        PatientInsurance foreign = new PatientInsurance();
        foreign.setPatient(patient(otherPatientId));
        when(insuranceRepository.findById(insuranceId)).thenReturn(Optional.of(foreign));
        String refused = catchThrowableOfType(ResourceNotFoundException.class,
            () -> service.getPatientInsuranceById(insuranceId, Locale.ENGLISH)).getMessage();
        when(insuranceRepository.findById(insuranceId)).thenReturn(Optional.empty());
        String missing = catchThrowableOfType(ResourceNotFoundException.class,
            () -> service.getPatientInsuranceById(insuranceId, Locale.ENGLISH)).getMessage();
        assertThat(refused).isEqualTo(missing);
    }

    @Test
    @DisplayName("acting as PATIENT with no ActingContext user id, the link is stamped with the appUserId")
    void linkStampsTheKeycloakUser() {
        Patient own = patient(ownPatientId);
        when(patientRepository.findById(ownPatientId)).thenReturn(Optional.of(own));
        when(insuranceRepository.findByPatient_IdAndPayerCodeIgnoreCaseAndPolicyNumberIgnoreCase(ownPatientId, "AETNA", "POL1"))
            .thenReturn(Optional.empty());
        when(insuranceRepository.save(any(PatientInsurance.class))).thenAnswer(inv -> inv.getArgument(0));
        LinkPatientInsuranceRequestDTO req = LinkPatientInsuranceRequestDTO.builder()
            .patientId(ownPatientId).payerCode("AETNA").policyNumber("POL1").build();

        service.upsertAndLinkByNaturalKey(req, new ActingContext(null, null, ActingMode.PATIENT, null), Locale.ENGLISH);

        org.mockito.ArgumentCaptor<PatientInsurance> saved = org.mockito.ArgumentCaptor.forClass(PatientInsurance.class);
        verify(insuranceRepository).save(saved.capture());
        assertThat(saved.getValue().getLinkedByUserId()).isEqualTo(callerUserId);
        assertThat(saved.getValue().getPatient()).isSameAs(own);
    }

    @Test
    @DisplayName("a patient cannot rewrite another patient's insurance by naming themselves in the body")
    void updateOfAnotherPatientsInsuranceAnswersAsMissing() {
        UUID insuranceId = UUID.randomUUID();
        PatientInsurance foreign = new PatientInsurance();
        foreign.setPatient(patient(otherPatientId));
        when(insuranceRepository.findById(insuranceId)).thenReturn(Optional.of(foreign));
        com.example.hms.payload.dto.PatientInsuranceRequestDTO dto = new com.example.hms.payload.dto.PatientInsuranceRequestDTO();
        dto.setPatientId(ownPatientId);

        String refused = catchThrowableOfType(ResourceNotFoundException.class,
            () -> service.updatePatientInsurance(insuranceId, dto, Locale.ENGLISH)).getMessage();
        when(insuranceRepository.findById(insuranceId)).thenReturn(Optional.empty());
        String missing = catchThrowableOfType(ResourceNotFoundException.class,
            () -> service.updatePatientInsurance(insuranceId, dto, Locale.ENGLISH)).getMessage();

        assertThat(refused).isEqualTo(missing);
        assertThat(foreign.getPatient().getId()).isEqualTo(otherPatientId);
        verify(insuranceRepository, never()).save(any());
    }

    @Test
    @DisplayName("linking another patient's insurance by id in STAFF mode answers as a missing id")
    void upsertByIdOfAnotherPatientsInsuranceAnswersAsMissing() {
        UUID insuranceId = UUID.randomUUID();
        PatientInsurance foreign = new PatientInsurance();
        foreign.setPatient(patient(otherPatientId));
        when(insuranceRepository.findById(insuranceId)).thenReturn(Optional.of(foreign));
        when(patientRepository.findById(ownPatientId)).thenReturn(Optional.of(patient(ownPatientId)));
        LinkPatientInsuranceRequestDTO req = LinkPatientInsuranceRequestDTO.builder().patientId(ownPatientId).build();
        ActingContext staffMode = new ActingContext(null, null, ActingMode.STAFF, null);

        String refused = catchThrowableOfType(ResourceNotFoundException.class,
            () -> service.upsertAndLinkByInsuranceId(insuranceId, req, staffMode, Locale.ENGLISH)).getMessage();
        when(insuranceRepository.findById(insuranceId)).thenReturn(Optional.empty());
        String missing = catchThrowableOfType(ResourceNotFoundException.class,
            () -> service.upsertAndLinkByInsuranceId(insuranceId, req, staffMode, Locale.ENGLISH)).getMessage();

        assertThat(refused).isEqualTo(missing);
        verify(insuranceRepository, never()).save(any());
    }
}
