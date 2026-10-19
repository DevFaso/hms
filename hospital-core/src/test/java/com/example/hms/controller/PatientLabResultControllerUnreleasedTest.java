package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.service.PatientLabResultService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D160: the staff route {@code GET /patients/{id}/lab-results} gave a
 * doctor the value the laboratory had not released yet. Only laboratory
 * staff now read an unreleased value; everyone else reads PENDING.
 */
class PatientLabResultControllerUnreleasedTest {

    private final PatientLabResultService service = mock(PatientLabResultService.class);
    private final ControllerAuthUtils authUtils = mock(ControllerAuthUtils.class);
    private final PatientLabResultController controller = new PatientLabResultController(service, authUtils);
    private final UUID patientId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();

    private Authentication as(String... roles) {
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        Authentication auth = new UsernamePasswordAuthenticationToken("someone", "n/a", authorities);
        when(authUtils.resolveHospitalScope(eq(auth), any(), eq(true))).thenReturn(hospitalId);
        when(authUtils.sanitizeLimit(any(), anyInt(), anyInt())).thenReturn(25);
        return auth;
    }

    @Test
    void aDoctorReadsTheUnreleasedRowWithoutItsValue() {
        Authentication auth = as("ROLE_DOCTOR");

        controller.listLabResults(patientId, null, null, auth);

        verify(service).getLabResultsForPatient(patientId, hospitalId, 25, false);
    }

    @Test
    void theLaboratoryReadsTheValueItHasToRelease() {
        Authentication auth = as("ROLE_LAB_SCIENTIST");

        controller.listLabResults(patientId, null, null, auth);

        verify(service).getLabResultsForPatient(patientId, hospitalId, 25, true);
    }
}
