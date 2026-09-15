package com.example.hms.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hms.service.FeatureFlagService;
import com.example.hms.security.JwtAuthenticationFilter;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = FeatureFlagController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration.class,
        org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration.class
    }
)
@AutoConfigureMockMvc(addFilters = false)
class FeatureFlagControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FeatureFlagService featureFlagService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void listFlagsReturnsEffectiveMap() throws Exception {
        when(featureFlagService.listFlags(eq(null), any(Locale.class)))
            .thenReturn(Map.of("departmentGovernance", true));

        mockMvc.perform(get("/feature-flags"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.departmentGovernance").value(true));
    }

    @Test
    void listFlagsSupportsEnvironmentOverride() throws Exception {
        when(featureFlagService.listFlags(eq("staging"), any(Locale.class)))
            .thenReturn(Map.of("departmentGovernance", true, "hospitalPortfolio", false));

        mockMvc.perform(get("/feature-flags").param("env", "staging"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hospitalPortfolio").value(false));
    }
}
