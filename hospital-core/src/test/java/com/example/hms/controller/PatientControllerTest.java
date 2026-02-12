package com.example.hms.controller;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.UserRoleHospitalAssignment;
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
import java.util.Optional;
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

    private PatientController controller;

    @BeforeEach
    void setUp() {
        controller = new PatientController(
            patientService,
            nurseDashboardService,
            patientChartUpdateService,
            messageSource,
            assignmentRepository
        );
    }

    @Test
    void receptionistFallsBackToAssignmentHospital() {
        UUID userId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);

        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setHospital(hospital);
    Role receptionistRole = new Role();
    receptionistRole.setCode(ROLE_RECEPTIONIST_CODE);
    assignment.setRole(receptionistRole);
        when(assignmentRepository.findFirstByUserIdAndRole_CodeIgnoreCaseAndActiveTrue(userId, ROLE_RECEPTIONIST_CODE))
            .thenReturn(Optional.of(assignment));

        List<PatientResponseDTO> expected = List.of(PatientResponseDTO.builder().id(UUID.randomUUID()).build());
        when(patientService.getAllPatients(eq(hospitalId), any(Locale.class))).thenReturn(expected);

        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("sub", userId.toString())
            .build();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt,
            List.of(new SimpleGrantedAuthority(ROLE_RECEPTIONIST_CODE)));

    ResponseEntity<List<PatientResponseDTO>> response = controller.getAllPatients(null, null, null, null, auth);

        assertEquals(expected, response.getBody());
        verify(patientService).getAllPatients(eq(hospitalId), any(Locale.class));
    }

    @Test
    void receptionistWithoutAssignmentThrowsBusinessException() {
        UUID userId = UUID.randomUUID();

        when(assignmentRepository.findFirstByUserIdAndRole_CodeIgnoreCaseAndActiveTrue(userId, ROLE_RECEPTIONIST_CODE))
            .thenReturn(Optional.empty());

        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("sub", userId.toString())
            .build();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt,
            List.of(new SimpleGrantedAuthority(ROLE_RECEPTIONIST_CODE)));

        assertThrows(BusinessException.class, () -> controller.getAllPatients(null, null, null, null, auth));
    }
}
