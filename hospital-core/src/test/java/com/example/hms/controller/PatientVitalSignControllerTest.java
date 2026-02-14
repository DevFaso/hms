package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.BusinessException;
import com.example.hms.payload.dto.PatientVitalSignRequestDTO;
import com.example.hms.payload.dto.PatientVitalSignResponseDTO;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientVitalSignControllerTest {

    @Mock
    private PatientVitalSignService patientVitalSignService;

    @Mock
    private ControllerAuthUtils authUtils;

    @InjectMocks
    private PatientVitalSignController controller;

    @Test
    void recordVital_scopesHospitalAndDelegatesToService() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Authentication auth = buildAuth("ROLE_NURSE");

        PatientVitalSignRequestDTO request = PatientVitalSignRequestDTO.builder()
            .heartRateBpm(90)
            .build();

        PatientVitalSignResponseDTO response = PatientVitalSignResponseDTO.builder()
            .id(UUID.randomUUID())
            .build();

        when(authUtils.resolveHospitalScope(auth, hospitalId, null, true)).thenReturn(hospitalId);
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
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
    }

    @Test
    void getRecentVitals_clampsLimitAndScopesHospital() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Authentication auth = buildAuth("ROLE_DOCTOR");

        when(authUtils.sanitizeLimit(500, 10, 50)).thenReturn(50);
        when(authUtils.resolveHospitalScope(auth, hospitalId, null, false)).thenReturn(hospitalId);
        when(patientVitalSignService.getRecentVitals(patientId, hospitalId, 50)).thenReturn(List.of());

        controller.getRecentVitals(patientId, hospitalId, 500, auth);

        verify(patientVitalSignService).getRecentVitals(patientId, hospitalId, 50);
    }

    @Test
    void listVitals_parsesDateFilters() {
        UUID patientId = UUID.randomUUID();
        Authentication auth = buildAuth("ROLE_HOSPITAL_ADMIN");

        LocalDateTime from = LocalDateTime.now().minusDays(1).withNano(0);
        LocalDateTime to = LocalDateTime.now().withNano(0);
        String fromStr = from.toString();
        String toStr = to.toString();

        when(authUtils.resolveHospitalScope(auth, null, null, false)).thenReturn(null);
        when(authUtils.parseDateTime(fromStr)).thenReturn(from);
        when(authUtils.parseDateTime(toStr)).thenReturn(to);
        when(authUtils.sanitizeLimit(20, 20, 200)).thenReturn(20);
        when(patientVitalSignService.getVitals(patientId, null, from, to, 0, 20))
            .thenReturn(List.of());

        controller.listVitals(patientId, null, fromStr, toStr, 0, 20, auth);

        verify(patientVitalSignService).getVitals(patientId, null, from, to, 0, 20);
    }

    @Test
    void listVitals_throwsForInvalidDate() {
        UUID patientId = UUID.randomUUID();
        Authentication auth = buildAuth("ROLE_HOSPITAL_ADMIN");

        when(authUtils.resolveHospitalScope(auth, null, null, false)).thenReturn(null);
        when(authUtils.parseDateTime("not-a-date")).thenThrow(new BusinessException("Invalid date format: not-a-date"));

        assertThrows(BusinessException.class,
            () -> controller.listVitals(patientId, null, "not-a-date", null, 0, 20, auth));
    }

    private Authentication buildAuth(String role) {
        var authorities = List.of(new SimpleGrantedAuthority(role));
        return new UsernamePasswordAuthenticationToken("user", null, authorities);
    }
}
