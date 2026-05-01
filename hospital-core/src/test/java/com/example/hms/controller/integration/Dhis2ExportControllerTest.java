package com.example.hms.controller.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.mapper.integration.Dhis2ExportRunMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.integration.Dhis2ExportRun;
import com.example.hms.model.integration.Dhis2ExportStatus;
import com.example.hms.model.integration.Dhis2PeriodType;
import com.example.hms.payload.dto.integration.Dhis2TriggerRequestDTO;
import com.example.hms.repository.integration.Dhis2ExportRunRepository;
import com.example.hms.security.JwtAuthenticationFilter;
import com.example.hms.service.integration.DhisAdxExportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = Dhis2ExportController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class
    }
)
@AutoConfigureMockMvc(addFilters = false)
class Dhis2ExportControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private DhisAdxExportService exportService;
    @MockitoBean private Dhis2ExportRunRepository runRepository;
    @MockitoBean private Dhis2ExportRunMapper runMapper;
    @MockitoBean private ControllerAuthUtils authUtils;
    @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;

    private UUID hospitalId;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        when(authUtils.resolveHospitalScope(any(), eq(hospitalId), any(Boolean.class)))
            .thenReturn(hospitalId);
        when(authUtils.resolveUserId(any())).thenReturn(Optional.of(UUID.randomUUID()));
    }

    @Test
    void triggerReturns200WithRunDto() throws Exception {
        Dhis2ExportRun run = sampleRun();
        when(exportService.triggerImmunizationsExport(
            eq(hospitalId), any(), any(), any(), any())).thenReturn(run);
        when(runMapper.toResponseDTO(run)).thenReturn(
            new com.example.hms.payload.dto.integration.Dhis2ExportRunResponseDTO(
                run.getId(), hospitalId, "DS00000DEFK", "202604", null,
                run.getStartedAt(), run.getCompletedAt(), Dhis2ExportStatus.SUCCESS,
                1, 0, 200, null, run.getRequestId()));

        Dhis2TriggerRequestDTO body = new Dhis2TriggerRequestDTO(
            hospitalId, "DS00000DEFK", Dhis2PeriodType.MONTHLY, "202604");

        mockMvc.perform(post("/admin/integrations/dhis2/exports/trigger")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.datasetUid").value("DS00000DEFK"));
    }

    @Test
    void triggerReturns400OnInvalidPeriodToken() throws Exception {
        // periodIso "2026-04" is the dashed form — backend pattern requires YYYYMM.
        String body = "{\"hospitalId\":\"" + hospitalId
            + "\",\"datasetUid\":\"DS00000DEFK\",\"periodType\":\"MONTHLY\",\"periodIso\":\"2026-04\"}";

        mockMvc.perform(post("/admin/integrations/dhis2/exports/trigger")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void listRunsReturnsPagedResult() throws Exception {
        Dhis2ExportRun run = sampleRun();
        Page<Dhis2ExportRun> page = new PageImpl<>(java.util.List.of(run));
        when(runRepository.findByHospital_IdOrderByStartedAtDesc(eq(hospitalId), any())).thenReturn(page);
        when(runMapper.toResponseDTO(run)).thenReturn(
            new com.example.hms.payload.dto.integration.Dhis2ExportRunResponseDTO(
                run.getId(), hospitalId, "DS00000DEFK", "202604", null,
                run.getStartedAt(), run.getCompletedAt(), Dhis2ExportStatus.SUCCESS,
                1, 0, 200, null, run.getRequestId()));

        mockMvc.perform(get("/admin/integrations/dhis2/exports/runs")
                .param("hospitalId", hospitalId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].id").value(run.getId().toString()));
    }

    @Test
    void getRunReturns404WhenAbsent() throws Exception {
        UUID runId = UUID.randomUUID();
        when(runRepository.findById(runId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/integrations/dhis2/exports/runs/" + runId))
            .andExpect(status().isNotFound());
    }

    private Dhis2ExportRun sampleRun() {
        Hospital h = new Hospital();
        h.setId(hospitalId);
        Dhis2ExportRun run = Dhis2ExportRun.builder()
            .hospital(h)
            .datasetUid("DS00000DEFK")
            .periodIso("202604")
            .startedAt(LocalDateTime.now())
            .completedAt(LocalDateTime.now())
            .status(Dhis2ExportStatus.SUCCESS)
            .valueCount(1)
            .skippedCount(0)
            .httpStatus(200)
            .requestId(UUID.randomUUID())
            .build();
        run.setId(UUID.randomUUID());
        return run;
    }
}
