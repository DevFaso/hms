package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.RecordAccessDenialReason;
import com.example.hms.enums.RecordAccessPosture;
import com.example.hms.enums.TenantIsolationMode;
import com.example.hms.enums.TreatmentRelationshipKind;
import com.example.hms.exception.ConflictException;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.recordaccess.RecordAccessPostureDTO;
import com.example.hms.payload.dto.recordaccess.RecordSharingOptOutDTO;
import com.example.hms.repository.PatientRepository;
import com.example.hms.security.JwtAuthenticationFilter;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.recordaccess.HospitalRecordAccessPostureService;
import com.example.hms.service.recordaccess.RecordAccessDecision;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import com.example.hms.service.recordaccess.RecordSharingOptOutService;
import com.example.hms.service.recordaccess.TreatmentRelationship;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wiring, status codes and the controller's own guards. Role gating is
 * {@code @PreAuthorize}, which this slice does not run (security
 * auto-configuration is excluded, as in every other controller slice here);
 * identity therefore arrives through the mocked {@link ControllerAuthUtils}.
 */
@WebMvcTest(
    controllers = {RecordAccessController.class, HospitalRecordAccessPostureController.class},
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class
    }
)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("RecordAccessController + HospitalRecordAccessPostureController")
class RecordAccessControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private RecordAccessPolicy recordAccessPolicy;
    @MockitoBean private RecordSharingOptOutService optOutService;
    @MockitoBean private HospitalRecordAccessPostureService postureService;
    @MockitoBean private PatientRepository patientRepository;
    @MockitoBean private ControllerAuthUtils authUtils;
    @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;

    private final UUID patientId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void identity() {
        when(authUtils.resolveUserId(any())).thenReturn(Optional.of(userId));
        when(authUtils.resolveHospitalScope(any(), isNull(UUID.class), anyBoolean())).thenReturn(hospitalId);
        when(authUtils.hasAuthority(any(), any())).thenReturn(false);
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    // ------------------------------------------------------------ decision

    @Test
    @DisplayName("GET record-access returns the carrier when permitted")
    void decidePermitted() throws Exception {
        TreatmentRelationship rel = new TreatmentRelationship(
            TreatmentRelationshipKind.ACTIVE_ADMISSION, UUID.randomUUID(), hospitalId, patientId,
            LocalDateTime.of(2026, 9, 1, 8, 0), null, true);
        when(recordAccessPolicy.decide(userId, patientId, hospitalId))
            .thenReturn(RecordAccessDecision.permitted(patientId, hospitalId, userId, rel,
                RecordAccessPosture.TREATMENT_PRESUMED));

        mockMvc.perform(get("/patients/{id}/record-access", patientId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.permitted").value(true))
            .andExpect(jsonPath("$.reason").value("PERMITTED"))
            .andExpect(jsonPath("$.relationshipKind").value("ACTIVE_ADMISSION"))
            .andExpect(jsonPath("$.actorDirectlyAttached").value(true))
            .andExpect(jsonPath("$.hospitalPosture").value("TREATMENT_PRESUMED"));
    }

    @Test
    @DisplayName("GET record-access names the gate that refused, with no carrier fields")
    void decideRefused() throws Exception {
        when(recordAccessPolicy.decide(userId, patientId, hospitalId))
            .thenReturn(RecordAccessDecision.refused(patientId, hospitalId, userId,
                RecordAccessDenialReason.PATIENT_OPTED_OUT, RecordAccessPosture.TREATMENT_PRESUMED));

        mockMvc.perform(get("/patients/{id}/record-access", patientId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.permitted").value(false))
            .andExpect(jsonPath("$.reason").value("PATIENT_OPTED_OUT"))
            .andExpect(jsonPath("$.relationshipKind").doesNotExist());
    }

    // ------------------------------------------------------------- opt-out

    @Test
    @DisplayName("a staff caller can record an opt-out for any patient")
    void staffOptsOut() throws Exception {
        when(authUtils.hasAuthority(any(), eq("ROLE_RECEPTIONIST"))).thenReturn(true);
        when(optOutService.optOut(eq(patientId), eq("wish"), eq(userId), any()))
            .thenReturn(new RecordSharingOptOutDTO(patientId, true, LocalDateTime.now(), "wish", null));

        mockMvc.perform(post("/patients/{id}/record-sharing/opt-out", patientId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"wish\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inForce").value(true));
    }

    @Test
    @DisplayName("a patient can opt out of their OWN record")
    void patientOptsOutOwn() throws Exception {
        Patient own = new Patient();
        own.setId(patientId);
        when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(own));
        when(optOutService.optOut(eq(patientId), isNull(), eq(userId), any()))
            .thenReturn(new RecordSharingOptOutDTO(patientId, true, LocalDateTime.now(), null, null));

        mockMvc.perform(post("/patients/{id}/record-sharing/opt-out", patientId))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a patient acting on SOMEONE ELSE's record is refused with 403, and the service is never called")
    void patientCannotTouchAnotherRecord() throws Exception {
        Patient own = new Patient();
        own.setId(UUID.randomUUID());
        when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(own));

        mockMvc.perform(post("/patients/{id}/record-sharing/opt-out", patientId))
            .andExpect(status().isForbidden());

        verify(optOutService, never()).optOut(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a second opt-out surfaces the service's 409")
    void optOutTwiceIs409() throws Exception {
        when(authUtils.hasAuthority(any(), eq("ROLE_HOSPITAL_ADMIN"))).thenReturn(true);
        when(optOutService.optOut(any(), any(), any(), any())).thenThrow(new ConflictException("already"));

        mockMvc.perform(post("/patients/{id}/record-sharing/opt-out", patientId))
            .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("DELETE revokes and returns the row no longer in force")
    void revoke() throws Exception {
        when(authUtils.hasAuthority(any(), eq("ROLE_HOSPITAL_ADMIN"))).thenReturn(true);
        when(optOutService.revoke(eq(patientId), eq(userId), any()))
            .thenReturn(new RecordSharingOptOutDTO(patientId, false, LocalDateTime.now().minusDays(1),
                null, LocalDateTime.now()));

        mockMvc.perform(delete("/patients/{id}/record-sharing/opt-out", patientId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inForce").value(false))
            .andExpect(jsonPath("$.revokedAt").exists());
    }

    // ------------------------------------------------------------- posture

    @Test
    @DisplayName("a hospital admin may set the posture of the hospital they are acting in")
    void adminSetsOwnHospital() throws Exception {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(hospitalId).superAdmin(false).build());
        when(postureService.set(hospitalId, RecordAccessPosture.EXPLICIT_CONSENT, userId))
            .thenReturn(new RecordAccessPostureDTO(hospitalId, RecordAccessPosture.EXPLICIT_CONSENT,
                TenantIsolationMode.ROW_LEVEL));

        mockMvc.perform(put("/hospitals/{id}/record-access-posture", hospitalId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"posture\":\"EXPLICIT_CONSENT\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.posture").value("EXPLICIT_CONSENT"));
    }

    @Test
    @DisplayName("a hospital admin may NOT set another hospital's posture")
    void adminCannotSetOtherHospital() throws Exception {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(UUID.randomUUID()).superAdmin(false).build());

        mockMvc.perform(put("/hospitals/{id}/record-access-posture", hospitalId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"posture\":\"EXPLICIT_CONSENT\"}"))
            .andExpect(status().isForbidden());

        verify(postureService, never()).set(any(), any(), any());
    }

    @Test
    @DisplayName("a super admin may set any hospital's posture")
    void superAdminSetsAny() throws Exception {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(UUID.randomUUID()).superAdmin(true).build());
        when(postureService.set(eq(hospitalId), eq(RecordAccessPosture.TREATMENT_PRESUMED), any()))
            .thenReturn(new RecordAccessPostureDTO(hospitalId, RecordAccessPosture.TREATMENT_PRESUMED,
                TenantIsolationMode.ROW_LEVEL));

        mockMvc.perform(put("/hospitals/{id}/record-access-posture", hospitalId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"posture\":\"TREATMENT_PRESUMED\"}"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a missing posture in the body is a 400, not a null write")
    void postureRequired() throws Exception {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(hospitalId).superAdmin(true).build());

        mockMvc.perform(put("/hospitals/{id}/record-access-posture", hospitalId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());

        verify(postureService, never()).set(any(), any(), any());
    }
}
