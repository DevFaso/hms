package com.example.hms.service;

import com.example.hms.model.Announcement;
import com.example.hms.payload.dto.AnnouncementResponseDTO;
import com.example.hms.repository.AnnouncementRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnnouncementServiceImplTest {

    @Mock
    private AnnouncementRepository announcementRepository;

    @InjectMocks
    private AnnouncementServiceImpl service;

    // ───────────── helpers ─────────────

    private Announcement buildAnnouncement(UUID id, String text, LocalDateTime date) {
        return Announcement.builder().id(id).text(text).date(date).build();
    }

    // ═══════════════ getAnnouncements ═══════════════

    @Nested
    @DisplayName("getAnnouncements")
    class GetAnnouncements {

        @Test
        @DisplayName("returns announcements sorted by date descending, limited")
        void returnsSortedAndLimited() {
            LocalDateTime oldest = LocalDateTime.of(2026, 1, 1, 8, 0);
            LocalDateTime middle = LocalDateTime.of(2026, 1, 5, 12, 0);
            LocalDateTime newest = LocalDateTime.of(2026, 1, 10, 16, 0);

            Announcement a1 = buildAnnouncement(UUID.randomUUID(), "Oldest", oldest);
            Announcement a2 = buildAnnouncement(UUID.randomUUID(), "Middle", middle);
            Announcement a3 = buildAnnouncement(UUID.randomUUID(), "Newest", newest);

            when(announcementRepository.findAll()).thenReturn(List.of(a1, a2, a3));

            List<AnnouncementResponseDTO> result = service.getAnnouncements(2);

            assertEquals(2, result.size());
            assertEquals("Newest", result.get(0).getText());
            assertEquals("Middle", result.get(1).getText());
            verify(announcementRepository).findAll();
        }

        @Test
        @DisplayName("returns empty list when repository is empty")
        void returnsEmptyList() {
            when(announcementRepository.findAll()).thenReturn(List.of());

            List<AnnouncementResponseDTO> result = service.getAnnouncements(5);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("returns all when limit exceeds total count")
        void limitExceedsCount() {
            Announcement a = buildAnnouncement(UUID.randomUUID(), "Only one", LocalDateTime.now());
            when(announcementRepository.findAll()).thenReturn(List.of(a));

            List<AnnouncementResponseDTO> result = service.getAnnouncements(100);

            assertEquals(1, result.size());
            assertEquals("Only one", result.get(0).getText());
        }
    }

    // ═══════════════ getAnnouncement ═══════════════

    @Nested
    @DisplayName("getAnnouncement")
    class GetAnnouncement {

        @Test
        @DisplayName("returns DTO when announcement exists")
        void found() {
            UUID id = UUID.randomUUID();
            LocalDateTime date = LocalDateTime.of(2026, 2, 11, 10, 0);
            Announcement a = buildAnnouncement(id, "Important", date);
            when(announcementRepository.findById(id)).thenReturn(Optional.of(a));

            AnnouncementResponseDTO dto = service.getAnnouncement(id);

            assertAll(
                () -> assertEquals(id, dto.getId()),
                () -> assertEquals("Important", dto.getText()),
                () -> assertEquals(date, dto.getDate())
            );
            verify(announcementRepository).findById(id);
        }

        @Test
        @DisplayName("throws RuntimeException when not found")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(announcementRepository.findById(id)).thenReturn(Optional.empty());

            RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.getAnnouncement(id));

            assertEquals("Announcement not found", ex.getMessage());
        }
    }

    // ═══════════════ createAnnouncement ═══════════════

    @Nested
    @DisplayName("createAnnouncement")
    class CreateAnnouncement {

        @Test
        @DisplayName("saves new announcement and returns DTO")
        void createsAndReturns() {
            UUID savedId = UUID.randomUUID();
            ArgumentCaptor<Announcement> captor = ArgumentCaptor.forClass(Announcement.class);

            when(announcementRepository.save(any(Announcement.class))).thenAnswer(inv -> {
                Announcement a = inv.getArgument(0);
                a.setId(savedId);
                return a;
            });

            AnnouncementResponseDTO dto = service.createAnnouncement("New policy");

            verify(announcementRepository).save(captor.capture());
            Announcement saved = captor.getValue();

            assertAll(
                () -> assertEquals("New policy", saved.getText()),
                () -> assertNotNull(saved.getDate()),
                () -> assertEquals(savedId, dto.getId()),
                () -> assertEquals("New policy", dto.getText())
            );
        }
    }

    // ═══════════════ updateAnnouncement ═══════════════

    @Nested
    @DisplayName("updateAnnouncement")
    class UpdateAnnouncement {

        @Test
        @DisplayName("updates text and returns DTO")
        void updatesAndReturns() {
            UUID id = UUID.randomUUID();
            LocalDateTime date = LocalDateTime.of(2026, 2, 1, 9, 0);
            Announcement existing = buildAnnouncement(id, "Old text", date);

            when(announcementRepository.findById(id)).thenReturn(Optional.of(existing));
            when(announcementRepository.save(any(Announcement.class))).thenAnswer(inv -> inv.getArgument(0));

            AnnouncementResponseDTO dto = service.updateAnnouncement(id, "New text");

            assertAll(
                () -> assertEquals(id, dto.getId()),
                () -> assertEquals("New text", dto.getText()),
                () -> assertEquals(date, dto.getDate())
            );
            verify(announcementRepository).findById(id);
            verify(announcementRepository).save(existing);
        }

        @Test
        @DisplayName("throws RuntimeException when not found")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(announcementRepository.findById(id)).thenReturn(Optional.empty());

            RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.updateAnnouncement(id, "text"));

            assertEquals("Announcement not found", ex.getMessage());
        }
    }

    // ═══════════════ deleteAnnouncement ═══════════════

    @Nested
    @DisplayName("deleteAnnouncement")
    class DeleteAnnouncement {

        @Test
        @DisplayName("delegates to repository deleteById")
        void deletesById() {
            UUID id = UUID.randomUUID();
            doNothing().when(announcementRepository).deleteById(id);

            service.deleteAnnouncement(id);

            verify(announcementRepository).deleteById(id);
        }
    }
}
