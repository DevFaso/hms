package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.PharmacyType;
import com.example.hms.exception.BusinessException;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.PharmacyDirectoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The pharmacy directory resolves its hospital through the validated request
 * context and {@link ControllerAuthUtils#resolveHospitalScope}, never from a
 * raw caller-supplied value.
 *
 * <p>{@code /pharmacies/patients/{id}} used to return the {@code hospitalId}
 * parameter, then the {@code X-Hospital-Id} header, unchecked. Driven through
 * MockMvc so the tests exercise the request as a client sends it, with the
 * {@link HospitalContextHolder} set the way the security filters leave it.
 */
class PharmacyDirectoryControllerScopeTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_A = UUID.randomUUID();
    private static final UUID HOSPITAL_B = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final String HEADER = "X-Hospital-Id";
    private static final String PATIENT_PATH = "/pharmacies/patients/{patientId}";
    private static final String COMMUNITY_PATH = "/pharmacies/community";

    private final PharmacyDirectoryService directoryService = mock(PharmacyDirectoryService.class);
    private final PharmacyRepository pharmacyRepository = mock(PharmacyRepository.class);
    private final UserRoleHospitalAssignmentRepository assignmentRepository =
        mock(UserRoleHospitalAssignmentRepository.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PharmacyDirectoryController controller = new PharmacyDirectoryController(
            directoryService, pharmacyRepository, new ControllerAuthUtils(assignmentRepository));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(directoryService.listPatientPharmacies(any(), any())).thenReturn(List.of());
        when(pharmacyRepository.findByHospitalIdAndPharmacyTypeAndActiveTrue(any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    // ── /pharmacies/patients/{patientId} ──────────────────────────────

    @Test
    @DisplayName("patients: a hospitalId the doctor does not hold is refused, not served")
    void patientPharmaciesRefuseAnUnheldHospitalParameter() throws Exception {
        clinicianAt(HOSPITAL_A);
        when(assignmentRepository.existsByUserIdAndHospitalIdAndActiveTrue(USER_ID, HOSPITAL_B)).thenReturn(false);

        // BusinessException is @ResponseStatus(BAD_REQUEST).
        mockMvc.perform(get(PATIENT_PATH, PATIENT_ID)
                .param("hospitalId", HOSPITAL_B.toString())
                .principal(doctor()))
            .andExpect(status().isBadRequest())
            .andExpect(result -> assertThat(result.getResolvedException())
                .isInstanceOf(BusinessException.class));
        verify(directoryService, never()).listPatientPharmacies(any(), any());
    }

    @Test
    @DisplayName("patients: a raw X-Hospital-Id the filter rejected is not consulted")
    void patientPharmaciesIgnoreARejectedHeader() throws Exception {
        clinicianAt(HOSPITAL_A);

        mockMvc.perform(get(PATIENT_PATH, PATIENT_ID)
                .header(HEADER, HOSPITAL_B.toString())
                .principal(doctor()))
            .andExpect(status().isOk());

        verify(directoryService).listPatientPharmacies(PATIENT_ID, HOSPITAL_A);
        verify(directoryService, never()).listPatientPharmacies(PATIENT_ID, HOSPITAL_B);
    }

    @Test
    @DisplayName("patients: a hospitalId the doctor holds is honoured")
    void patientPharmaciesHonourAHeldHospitalParameter() throws Exception {
        clinicianAt(HOSPITAL_A);
        when(assignmentRepository.existsByUserIdAndHospitalIdAndActiveTrue(USER_ID, HOSPITAL_B)).thenReturn(true);

        mockMvc.perform(get(PATIENT_PATH, PATIENT_ID)
                .param("hospitalId", HOSPITAL_B.toString())
                .principal(doctor()))
            .andExpect(status().isOk());

        verify(directoryService).listPatientPharmacies(PATIENT_ID, HOSPITAL_B);
    }

    @Test
    @DisplayName("patients: a super-admin's scope chip (a validated header) still scopes the call")
    void patientPharmaciesFollowASuperAdminChip() throws Exception {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(USER_ID)
            .activeHospitalId(HOSPITAL_B)
            .superAdmin(true)
            .headerOverridden(true)
            .build());

        mockMvc.perform(get(PATIENT_PATH, PATIENT_ID)
                .header(HEADER, HOSPITAL_B.toString())
                .principal(auth("ROLE_SUPER_ADMIN")))
            .andExpect(status().isOk());

        verify(directoryService).listPatientPharmacies(PATIENT_ID, HOSPITAL_B);
    }

    // ── /pharmacies/community ──────────────────────────────────────────

    @Test
    @DisplayName("community: a hospitalId the doctor does not hold is refused, not served")
    void communityPharmaciesRefuseAnUnheldHospitalParameter() throws Exception {
        clinicianAt(HOSPITAL_A);
        when(assignmentRepository.existsByUserIdAndHospitalIdAndActiveTrue(USER_ID, HOSPITAL_B)).thenReturn(false);

        // BusinessException is @ResponseStatus(BAD_REQUEST).
        mockMvc.perform(get(COMMUNITY_PATH)
                .param("hospitalId", HOSPITAL_B.toString())
                .principal(doctor()))
            .andExpect(status().isBadRequest())
            .andExpect(result -> assertThat(result.getResolvedException())
                .isInstanceOf(BusinessException.class));
        verify(pharmacyRepository, never()).findByHospitalIdAndPharmacyTypeAndActiveTrue(any(), any());
    }

    @Test
    @DisplayName("community: a raw X-Hospital-Id the filter rejected is not consulted")
    void communityPharmaciesIgnoreARejectedHeader() throws Exception {
        clinicianAt(HOSPITAL_A);

        mockMvc.perform(get(COMMUNITY_PATH)
                .header(HEADER, HOSPITAL_B.toString())
                .principal(doctor()))
            .andExpect(status().isOk());

        verify(pharmacyRepository).findByHospitalIdAndPharmacyTypeAndActiveTrue(HOSPITAL_A, PharmacyType.COMMUNITY_PHARMACY);
        verify(pharmacyRepository, never()).findByHospitalIdAndPharmacyTypeAndActiveTrue(HOSPITAL_B, PharmacyType.COMMUNITY_PHARMACY);
    }

    // ── helpers ────────────────────────────────────────────────────────

    /** The context the filters leave for a clinician assigned at one hospital. */
    private static void clinicianAt(UUID hospital) {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(USER_ID)
            .activeHospitalId(hospital)
            .permittedHospitalIds(Set.of(hospital))
            .build());
    }

    private static Authentication doctor() {
        return auth("ROLE_DOCTOR");
    }

    private static Authentication auth(String role) {
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("uid", USER_ID.toString())
            .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)));
    }
}
