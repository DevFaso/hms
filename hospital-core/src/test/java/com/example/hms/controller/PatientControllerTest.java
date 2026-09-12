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

        ResponseEntity<List<PatientResponseDTO>> response = controller.getAllPatients(null, null, null, null, auth);

        assertEquals(expected, response.getBody());
        verify(patientService).getAllPatients(eq(hospitalId), any(Locale.class));
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

        assertThrows(BusinessException.class, () -> controller.getAllPatients(null, null, null, null, auth));
    }
}
