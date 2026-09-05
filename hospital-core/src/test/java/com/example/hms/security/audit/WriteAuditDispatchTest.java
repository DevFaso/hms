package com.example.hms.security.audit;

import com.example.hms.enums.AuditEventType;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The interceptor sees what Spring MVC actually resolves — patterns, variables, status — not what a unit test hands it. */
@DisplayName("Write audit, through a real dispatch")
class WriteAuditDispatchTest {

    private static final UUID DOCTOR = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID EPISODE = UUID.randomUUID();

    @RestController
    @RequestMapping("/labor")
    static class LaborController {
        @PostMapping("/episodes/{patientId}")
        ResponseEntity<String> start(@PathVariable UUID patientId) {
            return ResponseEntity.status(201).body("{}");
        }

        @DeleteMapping("/episodes/{episodeId}")
        ResponseEntity<Void> abandon(@PathVariable UUID episodeId) {
            return ResponseEntity.noContent().build();
        }

        @PostMapping("/episodes/{episodeId}/reject")
        ResponseEntity<String> rejected(@PathVariable UUID episodeId) {
            return ResponseEntity.badRequest().body("no");
        }

        @GetMapping("/episodes/{episodeId}")
        ResponseEntity<String> read(@PathVariable UUID episodeId) {
            return ResponseEntity.ok("{}");
        }
    }

    private AuditEventLogService auditService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        auditService = mock(AuditEventLogService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AuditEventLogService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(auditService);
        WriteAuditInterceptor interceptor = new WriteAuditInterceptor(provider);
        ReflectionTestUtils.setField(interceptor, "enabled", true);
        mockMvc = MockMvcBuilders.standaloneSetup(new LaborController()).addInterceptors(interceptor).build();
        CustomUserDetails principal = new CustomUserDetails(
            DOCTOR, "dr.kabore", "x", true, List.of(new SimpleGrantedAuthority("ROLE_DOCTOR")));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private AuditEventRequestDTO emitted() {
        ArgumentCaptor<AuditEventRequestDTO> captor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditService).logEvent(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a real POST is recorded with the matched pattern, the entity and the patient")
    void recordsARealCreate() throws Exception {
        mockMvc.perform(post("/labor/episodes/{patientId}", PATIENT)).andExpect(status().isCreated());

        AuditEventRequestDTO row = emitted();
        assertThat(row.getEventType()).isEqualTo(AuditEventType.DATA_CREATE);
        assertThat(row.getEntityType()).isEqualTo("LABOR");
        assertThat(row.getPatientId()).isEqualTo(PATIENT);
        assertThat(row.getEventDescription()).isEqualTo("POST /labor/episodes/{patientId}");
        assertThat(row.getUserId()).isEqualTo(DOCTOR);
    }

    @Test
    @DisplayName("a real DELETE is recorded against the resource in the path")
    void recordsARealDelete() throws Exception {
        mockMvc.perform(delete("/labor/episodes/{episodeId}", EPISODE)).andExpect(status().isNoContent());

        AuditEventRequestDTO row = emitted();
        assertThat(row.getEventType()).isEqualTo(AuditEventType.DATA_DELETE);
        assertThat(row.getResourceId()).isEqualTo(EPISODE.toString());
        assertThat(row.getPatientId()).isNull();
    }

    @Test
    @DisplayName("a rejected write and a read record nothing")
    void ignoresFailuresAndReads() throws Exception {
        mockMvc.perform(post("/labor/episodes/{episodeId}/reject", EPISODE)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/labor/episodes/{episodeId}", EPISODE)).andExpect(status().isOk());
        verify(auditService, never()).logEvent(any());
    }
}
