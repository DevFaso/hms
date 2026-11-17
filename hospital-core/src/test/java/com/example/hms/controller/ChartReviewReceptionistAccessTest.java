package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.chartreview.ChartReviewDTO;
import com.example.hms.service.ChartReviewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** RCPT-CLIN (user decision 2026-11-06): chart review refuses a receptionist and answers a doctor, through real method security. */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = ChartReviewController.class,
    excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.example\\.hms\\.security\\..*"))
@Import(ChartReviewReceptionistAccessTest.MethodSecurity.class)
@DisplayName("RCPT-CLIN: chart review is not the front desk's")
class ChartReviewReceptionistAccessTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurity {
    }

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ChartReviewService chartReviewService;
    @MockitoBean private ControllerAuthUtils authUtils;

    private static final String URL = "/patients/" + UUID.randomUUID() + "/chart-review";

    @Test
    @WithMockUser(authorities = "ROLE_RECEPTIONIST")
    void aReceptionistIsRefusedAndNothingIsRead() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isForbidden());
        verify(chartReviewService, never()).getChartReview(any(), any(), any(), any(), anyInt());
    }

    @Test
    @WithMockUser(authorities = "ROLE_DOCTOR")
    void aDoctorReadsIt() throws Exception {
        when(chartReviewService.getChartReview(any(), any(), any(), any(), anyInt()))
            .thenReturn(new ChartReviewDTO());
        mockMvc.perform(get(URL)).andExpect(status().isOk());
    }
}
