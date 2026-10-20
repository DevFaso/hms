package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.BusinessException;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.NurseDashboardService;
import com.example.hms.service.PatientChartUpdateService;
import com.example.hms.service.PatientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientControllerTest {

    private static final String ROLE_RECEPTIONIST_CODE = "ROLE_RECEPTIONIST";

    @Mock
    private PatientService patientService;
    @Mock
    private NurseDashboardService nurseDashboardService;
    @Mock
    private PatientChartUpdateService patientChartUpdateService;
    @Mock
    private MessageSource messageSource;
    @Mock
    private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock
    private ControllerAuthUtils authUtils;

    private PatientController controller;

    @BeforeEach
    void setUp() {
        controller = new PatientController(
            patientService,
            nurseDashboardService,
            patientChartUpdateService,
            messageSource,
            assignmentRepository,
            authUtils
        );
    }

    @Test
    void listsPatientsInTheScopeAuthUtilsResolves() {
        UUID userId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        List<PatientResponseDTO> expected = List.of(PatientResponseDTO.builder().id(UUID.randomUUID()).build());
        when(patientService.getAllPatients(eq(hospitalId), any(Locale.class))).thenReturn(expected);

        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("sub", userId.toString())
            .build();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt,
            List.of(new SimpleGrantedAuthority(ROLE_RECEPTIONIST_CODE)));

        // E9 #55: the controller no longer resolves scope itself — one resolver.
        when(authUtils.resolveHospitalScope(auth, null, true)).thenReturn(hospitalId);

        ResponseEntity<List<PatientResponseDTO>> response = controller.getAllPatients(null, null, null, null, null, auth);

        assertEquals(expected, response.getBody());
        verify(patientService).getAllPatients(eq(hospitalId), any(Locale.class));
    }

    @Test
    void theSearchParameterFiltersInsteadOfBeingDropped() {
        // QA D79: the admission typeahead sends ?search=, which used to be
        // ignored, so the first patients of the hospital were offered.
        UUID hospitalId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
            .claim("sub", UUID.randomUUID().toString()).build();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt,
            List.of(new SimpleGrantedAuthority(ROLE_RECEPTIONIST_CODE)));
        when(authUtils.resolveHospitalScope(auth, null, true)).thenReturn(hospitalId);
        List<PatientResponseDTO> expected = List.of(PatientResponseDTO.builder().id(UUID.randomUUID()).build());
        org.mockito.ArgumentCaptor<com.example.hms.payload.dto.PatientSearchCriteria> criteria =
            org.mockito.ArgumentCaptor.forClass(com.example.hms.payload.dto.PatientSearchCriteria.class);
        when(patientService.searchPatients(criteria.capture(), eq(0), org.mockito.ArgumentMatchers.anyInt(),
            any(Locale.class))).thenReturn(expected);

        ResponseEntity<List<PatientResponseDTO>> response =
            controller.getAllPatients(null, null, null, " QAH5769 ", null, auth);

        assertEquals(expected, response.getBody());
        assertEquals("QAH5769", criteria.getValue().getName());
        assertEquals(hospitalId, criteria.getValue().getHospitalId());
        org.junit.jupiter.api.Assertions.assertTrue(criteria.getValue().isIncludeInactive());
        verify(patientService, never()).getAllPatients(any(), any());
    }

    @Test
    void propagatesTheResolverRefusal() {
        UUID userId = UUID.randomUUID();

        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("sub", userId.toString())
            .build();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt,
            List.of(new SimpleGrantedAuthority(ROLE_RECEPTIONIST_CODE)));

        when(authUtils.resolveHospitalScope(auth, null, true))
            .thenThrow(new BusinessException("Receptionist must be affiliated with a hospital."));

        assertThrows(BusinessException.class, () -> controller.getAllPatients(null, null, null, null, null, auth));
    }

    // QA D504: an allergy list narrowed by the patient's sharing opt-out says so in a header;
    // without it the chart shows a silent, complete-looking list.
    private JwtAuthenticationToken clinician(UUID userId) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
            .claim("sub", userId.toString()).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_DOCTOR")));
    }

    @Test
    void allergyListNarrowedByAnOptOutCarriesTheWithheldHeader() {
        UUID userId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        JwtAuthenticationToken auth = clinician(userId);
        when(authUtils.resolveUserId(auth)).thenReturn(java.util.Optional.of(userId));
        when(authUtils.resolveHospitalScope(auth, null, false)).thenReturn(hospitalId);
        when(patientService.getPatientAllergies(patientId, hospitalId, userId)).thenReturn(List.of());
        when(patientService.foreignAllergiesWithheld(patientId, hospitalId, userId)).thenReturn(true);

        ResponseEntity<List<com.example.hms.payload.dto.PatientAllergyResponseDTO>> response =
            controller.getPatientAllergies(patientId, null, auth);

        assertEquals(PatientController.RECORDS_WITHHELD_OTHER_HOSPITALS,
            response.getHeaders().getFirst(PatientController.RECORDS_WITHHELD_HEADER));
    }

    @Test
    void allergyListUnderBreakGlassHasNoWithheldHeader() {
        UUID userId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        JwtAuthenticationToken auth = clinician(userId);
        when(authUtils.resolveUserId(auth)).thenReturn(java.util.Optional.of(userId));
        when(authUtils.resolveHospitalScope(auth, null, false)).thenReturn(hospitalId);
        when(patientService.getPatientAllergies(patientId, hospitalId, userId)).thenReturn(List.of());
        when(patientService.foreignAllergiesWithheld(patientId, hospitalId, userId)).thenReturn(false);

        ResponseEntity<List<com.example.hms.payload.dto.PatientAllergyResponseDTO>> response =
            controller.getPatientAllergies(patientId, null, auth);

        org.junit.jupiter.api.Assertions.assertNull(
            response.getHeaders().getFirst(PatientController.RECORDS_WITHHELD_HEADER));
    }
}
