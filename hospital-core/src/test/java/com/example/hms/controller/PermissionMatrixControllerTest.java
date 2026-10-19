package com.example.hms.controller;

import com.example.hms.enums.PermissionMatrixEnvironment;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.payload.dto.PermissionMatrixSnapshotResponseDTO;
import com.example.hms.service.PermissionMatrixService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** QA D248: an environment with no snapshot yet is an empty state (204), not a 404. */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = PermissionMatrixController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.example\\.hms\\.security\\..*"
    )
)
@Import(PermissionMatrixControllerTest.Config.class)
class PermissionMatrixControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PermissionMatrixService service;

    @AfterEach
    void reset() {
        Mockito.reset(service);
    }

    @Test
    @WithMockUser
    @DisplayName("no snapshot for the environment answers 204 with no body")
    void missingSnapshotIsNoContent() throws Exception {
        when(service.getLatestSnapshot(eq(PermissionMatrixEnvironment.CURRENT), any()))
            .thenThrow(new ResourceNotFoundException("permission.matrix.snapshot.notfound"));

        mockMvc.perform(get("/permissions/matrix/snapshots/latest").param("environment", "CURRENT"))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
    }

    @Test
    @WithMockUser
    @DisplayName("an existing snapshot is still served with 200")
    void existingSnapshotIsOk() throws Exception {
        when(service.getLatestSnapshot(eq(PermissionMatrixEnvironment.CURRENT), any()))
            .thenReturn(new PermissionMatrixSnapshotResponseDTO());

        mockMvc.perform(get("/permissions/matrix/snapshots/latest").param("environment", "CURRENT"))
            .andExpect(status().isOk());
    }

    @TestConfiguration
    static class Config {
        @Bean
        PermissionMatrixService permissionMatrixService() {
            return Mockito.mock(PermissionMatrixService.class);
        }
    }
}
