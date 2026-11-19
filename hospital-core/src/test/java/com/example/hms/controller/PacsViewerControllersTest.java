package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.imaging.dicom.DicomProxyService;
import com.example.hms.imaging.dicom.PacsUnavailableException;
import com.example.hms.imaging.dicom.RenderedFrame;
import com.example.hms.payload.dto.imaging.pacs.ImagingStudyViewerDTO;
import com.example.hms.payload.dto.imaging.pacs.PacsSettingsDTO;
import com.example.hms.security.JwtAuthenticationFilter;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.imaging.ImagingStudyViewerService;
import com.example.hms.service.imaging.PacsSettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The PACS settings and the in-app viewer endpoints (V317): wiring, status
 * codes, the acting-hospital check, headers. {@code @PreAuthorize} does not run
 * in a slice; the role sets are pinned by reflection below and by
 * {@code ImagingReadRoleGuardTest}.
 */
@WebMvcTest(
    controllers = {HospitalPacsSettingsController.class, DicomProxyController.class},
    excludeAutoConfiguration = {
        org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration.class,
        org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration.class
    }
)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PACS settings + viewer endpoints")
class PacsViewerControllersTest {

    private static final String SERIES = "1.2.840.1.1";
    private static final String SOP = "1.2.840.1.1.1";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PacsSettingsService pacsSettingsService;
    @MockitoBean private ImagingStudyViewerService viewerService;
    @MockitoBean private DicomProxyService dicomProxyService;
    @MockitoBean private ControllerAuthUtils authUtils;
    @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID reportId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void identity() {
        when(authUtils.resolveUserId(any())).thenReturn(Optional.of(userId));
    }

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
    }

    private PacsSettingsDTO settings() {
        return new PacsSettingsDTO(hospitalId, true, "https://pacs.example/dicom-web", null, "orthanc", true,
            "READY", "HOSPITAL", true, false, false);
    }

    @Test
    @DisplayName("a hospital admin reads their hospital's PACS settings: passwordSet, never the password")
    void adminReadsOwnHospital() throws Exception {
        HospitalContextHolder.setContext(HospitalContext.builder().activeHospitalId(hospitalId).superAdmin(false).build());
        when(pacsSettingsService.get(hospitalId)).thenReturn(settings());

        mockMvc.perform(get("/hospitals/{id}/pacs-settings", hospitalId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.passwordSet").value(true))
            .andExpect(jsonPath("$.authPassword").doesNotExist())
            .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    @DisplayName("a hospital admin may not read or set another hospital's PACS")
    void adminCannotReachOtherHospital() throws Exception {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(UUID.randomUUID()).superAdmin(false).build());

        mockMvc.perform(get("/hospitals/{id}/pacs-settings", hospitalId)).andExpect(status().isForbidden());
        mockMvc.perform(put("/hospitals/{id}/pacs-settings", hospitalId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"dicomwebBaseUrl\":\"https://pacs.example/dicom-web\"}"))
            .andExpect(status().isForbidden());
        verify(pacsSettingsService, never()).set(any(), any(), any());
    }

    @Test
    @DisplayName("the password goes in and the answer never echoes it")
    void passwordNeverEchoed() throws Exception {
        HospitalContextHolder.setContext(HospitalContext.builder().activeHospitalId(hospitalId).superAdmin(false).build());
        when(pacsSettingsService.set(eq(hospitalId), any(), eq(userId))).thenReturn(settings());

        String body = mockMvc.perform(put("/hospitals/{id}/pacs-settings", hospitalId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"dicomwebBaseUrl\":\"https://pacs.example/dicom-web\","
                    + "\"authUsername\":\"orthanc\",\"authPassword\":\"Top-S3cret\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Top-S3cret");
    }

    @Test
    @DisplayName("a missing switch is a 400")
    void enabledRequired() throws Exception {
        HospitalContextHolder.setContext(HospitalContext.builder().activeHospitalId(hospitalId).superAdmin(false).build());
        mockMvc.perform(put("/hospitals/{id}/pacs-settings", hospitalId)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        verify(pacsSettingsService, never()).set(any(), any(), any());
    }

    @Test
    @DisplayName("viewer: the state comes back uncached; a report of another hospital is a 404")
    void viewerStatesAndTenancy() throws Exception {
        when(viewerService.manifest(reportId, userId))
            .thenReturn(new ImagingStudyViewerDTO(reportId, "PROXY_DISABLED", null, List.of(), false));
        mockMvc.perform(get("/imaging/dicom/reports/{id}/viewer", reportId))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.status").value("PROXY_DISABLED"));

        UUID other = UUID.randomUUID();
        when(viewerService.manifest(other, userId)).thenThrow(new ResourceNotFoundException("imaging.report.notFound", other));
        mockMvc.perform(get("/imaging/dicom/reports/{id}/viewer", other)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a rendered frame streams as an image, never cached; the PACS down is a 502; a bad UID a 400")
    void renderedFrame() throws Exception {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, 1};
        when(viewerService.frame(reportId, SERIES, SOP, 1)).thenReturn(new RenderedFrame(jpeg, "image/jpeg"));
        mockMvc.perform(get("/imaging/dicom/reports/{r}/series/{s}/instances/{i}/frames/{f}/rendered",
                reportId, SERIES, SOP, 1))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/jpeg"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(content().bytes(jpeg));

        when(viewerService.frame(reportId, SERIES, SOP, 2))
            .thenThrow(new PacsUnavailableException(PacsUnavailableException.Reason.UNREACHABLE));
        mockMvc.perform(get("/imaging/dicom/reports/{r}/series/{s}/instances/{i}/frames/{f}/rendered",
                reportId, SERIES, SOP, 2))
            .andExpect(status().isBadGateway());

        when(viewerService.frame(eq(reportId), eq("9.9"), anyString(), anyInt()))
            .thenThrow(new BusinessException("imaging.pacs.uid.invalid"));
        mockMvc.perform(get("/imaging/dicom/reports/{r}/series/{s}/instances/{i}/frames/{f}/rendered",
                reportId, "9.9", SOP, 1))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("roles: PACS settings are HOSPITAL_ADMIN / SUPER_ADMIN only")
    void settingsRoles() {
        for (Method method : HospitalPacsSettingsController.class.getDeclaredMethods()) {
            PreAuthorize gate = method.getAnnotation(PreAuthorize.class);
            if (gate != null) {
                assertThat(gate.value()).as(method.getName())
                    .isEqualTo("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')");
            }
        }
    }
}
