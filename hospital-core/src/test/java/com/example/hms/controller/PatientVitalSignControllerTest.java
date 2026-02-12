package com.example.hms.controller;

import com.example.hms.exception.BusinessException;
import com.example.hms.payload.dto.PatientVitalSignRequestDTO;
import com.example.hms.payload.dto.PatientVitalSignResponseDTO;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.service.PatientVitalSignService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientVitalSignControllerTest {

    @Mock
    private PatientVitalSignService patientVitalSignService;

    @Mock
    private UserRoleHospitalAssignmentRepository assignmentRepository;

    @InjectMocks
    private PatientVitalSignController controller;

    @Test
    void recordVital_scopesHospitalAndDelegatesToService() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Authentication auth = buildAuth(userId, "ROLE_NURSE");

        PatientVitalSignRequestDTO request = PatientVitalSignRequestDTO.builder()
            .heartRateBpm(90)
            .build();

        PatientVitalSignResponseDTO response = PatientVitalSignResponseDTO.builder()
            .id(UUID.randomUUID())
            .build();

        when(patientVitalSignService.recordVital(eq(patientId), any(PatientVitalSignRequestDTO.class), eq(userId)))
            .thenReturn(response);

        ResponseEntity<PatientVitalSignResponseDTO> result = controller.recordVital(patientId, request, hospitalId, auth);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertNotNull(result.getBody());
        assertEquals(response, result.getBody());
        assertEquals(hospitalId, request.getHospitalId());
        ArgumentCaptor<PatientVitalSignRequestDTO> captor = ArgumentCaptor.forClass(PatientVitalSignRequestDTO.class);
        verify(patientVitalSignService).recordVital(eq(patientId), captor.capture(), eq(userId));
        assertEquals(hospitalId, captor.getValue().getHospitalId());
        verify(assignmentRepository, never()).findFirstByUserIdAndActiveTrueOrderByCreatedAtDesc(any());
    }

    @Test
    void getRecentVitals_clampsLimitAndScopesHospital() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Authentication auth = buildAuth(userId, "ROLE_DOCTOR");

        when(patientVitalSignService.getRecentVitals(patientId, hospitalId, 50)).thenReturn(List.of());

        controller.getRecentVitals(patientId, hospitalId, 500, auth);

        verify(patientVitalSignService).getRecentVitals(patientId, hospitalId, 50);
    }

    @Test
    void listVitals_parsesDateFilters() {
        UUID patientId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Authentication auth = buildAuth(userId, "ROLE_HOSPITAL_ADMIN");

        LocalDateTime from = LocalDateTime.now().minusDays(1).withNano(0);
        LocalDateTime to = LocalDateTime.now().withNano(0);
        String fromStr = from.toString();
        String toStr = to.toString();

        when(patientVitalSignService.getVitals(patientId, null, from, to, 0, 20))
            .thenReturn(List.of());

        controller.listVitals(patientId, null, fromStr, toStr, 0, 20, auth);

        verify(patientVitalSignService).getVitals(patientId, null, from, to, 0, 20);
    }

    @Test
    void listVitals_throwsForInvalidDate() {
        UUID patientId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Authentication auth = buildAuth(userId, "ROLE_HOSPITAL_ADMIN");

        assertThrows(BusinessException.class,
            () -> controller.listVitals(patientId, null, "not-a-date", null, 0, 20, auth));
    }

    private Authentication buildAuth(UUID userId, String role) {
        var authorities = List.of(new SimpleGrantedAuthority(role));
        CustomUserDetails principal = new CustomUserDetails(userId, "user", "secret", true, authorities);
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }
}
