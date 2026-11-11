package com.example.hms.controller.support;

import com.example.hms.controller.EncounterController;
import com.example.hms.controller.PatientLabResultController;
import com.example.hms.controller.PatientMedicationController;
import com.example.hms.controller.PatientVitalSignController;
import com.example.hms.service.EncounterService;
import com.example.hms.service.PatientLabResultService;
import com.example.hms.service.PatientMedicationService;
import com.example.hms.service.PatientVitalSignService;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * QA D504: every patient clinical list outside PatientController (labs,
 * medications, vitals, encounters) says when the patient's sharing opt-out
 * held other hospitals' rows back, and says nothing otherwise.
 */
class RecordsWithheldHeadersTest {

    private final RecordAccessPolicy policy = mock(RecordAccessPolicy.class);
    private final ControllerAuthUtils authUtils = mock(ControllerAuthUtils.class);
    private final RecordsWithheldHeaders headers = new RecordsWithheldHeaders(policy, authUtils);
    private final UUID actor = UUID.randomUUID();
    private final UUID patient = UUID.randomUUID();
    private final UUID hospital = UUID.randomUUID();
    private final Authentication auth = new UsernamePasswordAuthenticationToken(
        "someone", "n/a", List.of(new SimpleGrantedAuthority("ROLE_DOCTOR")));

    @BeforeEach
    void scope() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(actor));
        when(authUtils.resolveHospitalScope(eq(auth), any(), any(Boolean.class))).thenReturn(hospital);
        when(authUtils.resolveHospitalScope(eq(auth), any(), any(), any(Boolean.class))).thenReturn(hospital);
        when(authUtils.sanitizeLimit(any(), anyInt(), anyInt())).thenReturn(10);
        when(authUtils.currentHospitalId(auth)).thenReturn(hospital);
    }

    private void withheld(boolean value) {
        when(policy.foreignRowsWithheld(actor, patient, hospital)).thenReturn(value);
    }

    private static String header(ResponseEntity<?> response) {
        return response.getHeaders().getFirst(RecordsWithheldHeaders.HEADER);
    }

    @Test
    @DisplayName("no patient or no acting hospital: nothing to say and the policy is not asked")
    void noScopeNoHeader() {
        assertThat(headers.forRead(auth, null, hospital).isEmpty()).isTrue();
        assertThat(headers.forRead(auth, patient, null).isEmpty()).isTrue();
        verifyNoInteractions(policy);
    }

    @Test
    @DisplayName("labs: withheld rows are signalled, a complete list is not")
    void labs() {
        PatientLabResultController controller = new PatientLabResultController(
            mock(PatientLabResultService.class), authUtils, headers);
        withheld(true);
        assertThat(header(controller.listLabResults(patient, null, null, auth))).isEqualTo("OTHER_HOSPITALS");
        withheld(false);
        assertThat(header(controller.listLabResults(patient, null, null, auth))).isNull();
    }

    @Test
    @DisplayName("medications: withheld rows are signalled")
    void medications() {
        PatientMedicationController controller = new PatientMedicationController(
            mock(PatientMedicationService.class), authUtils, headers);
        withheld(true);
        assertThat(header(controller.listMedications(patient, null, null, auth))).isEqualTo("OTHER_HOSPITALS");
        withheld(false);
        assertThat(header(controller.listMedications(patient, null, null, auth))).isNull();
    }

    @Test
    @DisplayName("vitals, recent and historical: withheld rows are signalled")
    void vitals() {
        PatientVitalSignController controller = new PatientVitalSignController(
            mock(PatientVitalSignService.class), authUtils, headers);
        withheld(true);
        assertThat(header(controller.getRecentVitals(patient, null, 10, auth))).isEqualTo("OTHER_HOSPITALS");
        assertThat(header(controller.listVitals(patient, null, null, null, 0, 20, auth))).isEqualTo("OTHER_HOSPITALS");
        withheld(false);
        assertThat(header(controller.getRecentVitals(patient, null, 10, auth))).isNull();
    }

    @Test
    @DisplayName("encounters for a patient: withheld rows are signalled; a list with no patient says nothing")
    void encounters() {
        EncounterService service = mock(EncounterService.class);
        when(service.list(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Page.empty());
        EncounterController controller = new EncounterController(service, authUtils, headers,
            mock(com.example.hms.service.MedicalCertificateService.class));
        withheld(true);
        assertThat(header(controller.list(patient, null, null, null, null, null,
            PageRequest.of(0, 20), Locale.ENGLISH, auth))).isEqualTo("OTHER_HOSPITALS");
        assertThat(header(controller.list(null, null, null, null, null, null,
            PageRequest.of(0, 20), Locale.ENGLISH, auth))).isNull();
    }
}
