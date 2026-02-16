package com.example.hms.controller;

import com.example.hms.payload.dto.AnnouncementResponseDTO;
import com.example.hms.service.AnnouncementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = AnnouncementController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.example\\.hms\\.security\\..*"
    )
)
@Import(AnnouncementControllerTest.Config.class)
class AnnouncementControllerTest {

    private static final String BASE = "/announcements";

    @Autowired private MockMvc mockMvc;
    @Autowired private AnnouncementService announcementService;

    @AfterEach
    void resetMocks() {
        Mockito.reset(announcementService);
    }

    private AnnouncementResponseDTO buildResponse(UUID id, String text) {
        return AnnouncementResponseDTO.builder()
            .id(id)
            .text(text)
            .date(LocalDateTime.of(2026, 2, 11, 10, 0))
            .build();
    }

    // ───────────── GET /announcements ─────────────

    @Test
    @WithMockUser
    @DisplayName("GET /announcements — returns list with default limit")
    void getAnnouncements_defaultLimit() throws Exception {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(announcementService.getAnnouncements(5))
            .thenReturn(List.of(buildResponse(id1, "A1"), buildResponse(id2, "A2")));

        mockMvc.perform(get(BASE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value(id1.toString()))
            .andExpect(jsonPath("$[1].text").value("A2"));

        verify(announcementService).getAnnouncements(5);
    }

    @Test
    @WithMockUser
    @DisplayName("GET /announcements?limit=10 — passes custom limit")
    void getAnnouncements_customLimit() throws Exception {
        when(announcementService.getAnnouncements(10)).thenReturn(List.of());

        mockMvc.perform(get(BASE).param("limit", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        verify(announcementService).getAnnouncements(10);
    }

    // ───────────── GET /announcements/{id} ─────────────

    @Test
    @WithMockUser
    @DisplayName("GET /announcements/{id} — returns announcement")
    void getAnnouncement_ok() throws Exception {
        UUID id = UUID.randomUUID();
        when(announcementService.getAnnouncement(id)).thenReturn(buildResponse(id, "Important update"));

        mockMvc.perform(get(BASE + "/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id.toString()))
            .andExpect(jsonPath("$.text").value("Important update"));

        verify(announcementService).getAnnouncement(id);
    }

    // ───────────── POST /announcements ─────────────

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    @DisplayName("POST /announcements — creates announcement")
    void createAnnouncement_ok() throws Exception {
        UUID id = UUID.randomUUID();
        when(announcementService.createAnnouncement("New policy effective Monday"))
            .thenReturn(buildResponse(id, "New policy effective Monday"));

        mockMvc.perform(post(BASE).param("text", "New policy effective Monday"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id.toString()))
            .andExpect(jsonPath("$.text").value("New policy effective Monday"));

        verify(announcementService).createAnnouncement("New policy effective Monday");
    }

    // ───────────── PUT /announcements/{id} ─────────────

    @Test
    @WithMockUser(roles = "HOSPITAL_ADMIN")
    @DisplayName("PUT /announcements/{id} — updates announcement")
    void updateAnnouncement_ok() throws Exception {
        UUID id = UUID.randomUUID();
        when(announcementService.updateAnnouncement(id, "Updated text"))
            .thenReturn(buildResponse(id, "Updated text"));

        mockMvc.perform(put(BASE + "/{id}", id).param("text", "Updated text"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.text").value("Updated text"));

        verify(announcementService).updateAnnouncement(id, "Updated text");
    }

    // ───────────── DELETE /announcements/{id} ─────────────

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    @DisplayName("DELETE /announcements/{id} — returns 204")
    void deleteAnnouncement_noContent() throws Exception {
        UUID id = UUID.randomUUID();
        doNothing().when(announcementService).deleteAnnouncement(id);

        mockMvc.perform(delete(BASE + "/{id}", id))
            .andExpect(status().isNoContent());

        verify(announcementService).deleteAnnouncement(id);
    }

    // ───────────── config ─────────────

    @TestConfiguration
    static class Config {
        @Bean
        AnnouncementService announcementService() {
            return Mockito.mock(AnnouncementService.class);
        }
    }
}
