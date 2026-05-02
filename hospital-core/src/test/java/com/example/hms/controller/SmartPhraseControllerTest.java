package com.example.hms.controller;

import com.example.hms.enums.SmartPhraseScope;
import com.example.hms.payload.dto.SmartPhraseRequestDTO;
import com.example.hms.payload.dto.SmartPhraseResponseDTO;
import com.example.hms.service.SmartPhraseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage for {@link SmartPhraseController}. Pins:
 *  - 201 on create / 200 on update / 204 on delete + recordUsage
 *  - autocomplete passes through service results
 *  - validation: malformed trigger (no leading dot) returns 400
 *  - listGlobal restricted to admin roles in production; addFilters=false here
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = SmartPhraseController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.example\\.hms\\.security\\..*"
    )
)
@Import(SmartPhraseControllerTest.ControllerTestConfig.class)
class SmartPhraseControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SmartPhraseService smartPhraseService;

    @AfterEach
    void resetMocks() {
        Mockito.reset(smartPhraseService);
    }

    private SmartPhraseRequestDTO globalRequest(String trigger) {
        return SmartPhraseRequestDTO.builder()
            .trigger(trigger)
            .title("Normal ROS")
            .expansion("Constitutional: denies fever, …")
            .scope(SmartPhraseScope.GLOBAL)
            .build();
    }

    private SmartPhraseResponseDTO buildResponse(String trigger, SmartPhraseScope scope) {
        return SmartPhraseResponseDTO.builder()
            .id(UUID.randomUUID())
            .trigger(trigger)
            .title("Normal ROS")
            .expansion("Constitutional: denies fever, …")
            .scope(scope)
            .usageCount(0L)
            .build();
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    @DisplayName("POST /smart-phrases returns 201")
    void create_returnsCreated() throws Exception {
        SmartPhraseRequestDTO req = globalRequest(".normros");
        SmartPhraseResponseDTO resp = buildResponse(".normros", SmartPhraseScope.GLOBAL);
        when(smartPhraseService.create(any(SmartPhraseRequestDTO.class))).thenReturn(resp);

        mockMvc.perform(post("/smart-phrases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.trigger").value(".normros"))
            .andExpect(jsonPath("$.scope").value("GLOBAL"));

        verify(smartPhraseService).create(any(SmartPhraseRequestDTO.class));
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    @DisplayName("POST /smart-phrases with malformed trigger returns 400")
    void create_rejectsBadTrigger() throws Exception {
        SmartPhraseRequestDTO req = globalRequest("normros");

        mockMvc.perform(post("/smart-phrases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(smartPhraseService);
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    @DisplayName("PUT /smart-phrases/{id} returns 200")
    void update_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        SmartPhraseRequestDTO req = globalRequest(".normros");
        SmartPhraseResponseDTO resp = buildResponse(".normros", SmartPhraseScope.GLOBAL);
        when(smartPhraseService.update(eq(id), any(SmartPhraseRequestDTO.class))).thenReturn(resp);

        mockMvc.perform(put("/smart-phrases/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.trigger").value(".normros"));
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    @DisplayName("DELETE /smart-phrases/{id} returns 204")
    void delete_returns204() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/smart-phrases/{id}", id))
            .andExpect(status().isNoContent());

        verify(smartPhraseService).delete(id);
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    @DisplayName("GET /smart-phrases/{id} returns 200")
    void getById_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        SmartPhraseResponseDTO resp = buildResponse(".normexam", SmartPhraseScope.GLOBAL);
        when(smartPhraseService.get(id)).thenReturn(resp);

        mockMvc.perform(get("/smart-phrases/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.trigger").value(".normexam"));
    }

    @Test
    @WithMockUser(authorities = {"ROLE_HOSPITAL_ADMIN"})
    @DisplayName("GET /smart-phrases/global pages through GLOBAL")
    void listGlobal_returnsPage() throws Exception {
        SmartPhraseResponseDTO resp = buildResponse(".normros", SmartPhraseScope.GLOBAL);
        Page<SmartPhraseResponseDTO> page = new PageImpl<>(List.of(resp));
        when(smartPhraseService.listGlobal(any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/smart-phrases/global"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].trigger").value(".normros"));
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    @DisplayName("GET /smart-phrases/autocomplete passes through service results")
    void autocomplete_passesThrough() throws Exception {
        UUID hospitalId = UUID.randomUUID();
        SmartPhraseResponseDTO resp = buildResponse(".normexam", SmartPhraseScope.HOSPITAL);
        when(smartPhraseService.autocomplete(eq(".norm"), eq(hospitalId)))
            .thenReturn(List.of(resp));

        mockMvc.perform(get("/smart-phrases/autocomplete")
                .param("prefix", ".norm")
                .param("hospitalId", hospitalId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].trigger").value(".normexam"))
            .andExpect(jsonPath("$[0].scope").value("HOSPITAL"));
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR"})
    @DisplayName("POST /smart-phrases/{id}/usage returns 204")
    void recordUsage_returns204() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/smart-phrases/{id}/usage", id))
            .andExpect(status().isNoContent());

        verify(smartPhraseService).recordUsage(id);
    }

    static class ControllerTestConfig {
        @Bean
        SmartPhraseService smartPhraseService() {
            return Mockito.mock(SmartPhraseService.class);
        }
    }
}
