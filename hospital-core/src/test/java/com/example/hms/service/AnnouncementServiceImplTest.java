package com.example.hms.service;

import com.example.hms.security.tenant.ActingScopeTestSupport;
import com.example.hms.model.Announcement;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AnnouncementResponseDTO;
import com.example.hms.repository.AnnouncementRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertAll;
import org.mockito.Spy;
import org.springframework.context.MessageSource;
import com.example.hms.i18n.TestMessageSources;

@ExtendWith(MockitoExtension.class)
class AnnouncementServiceImplTest {

    @Mock
    private AnnouncementRepository announcementRepository;

    @Mock
    private HospitalRepository hospitalRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private UserRoleHospitalAssignmentRepository assignmentRepository;

    @Spy private MessageSource messageSource = TestMessageSources.bundles();

    @InjectMocks
    private AnnouncementServiceImpl service;

    @AfterEach
    void clearHospitalContext() {
        HospitalContextHolder.clear();
    }

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
            // Every hospital's announcements: a super-admin in global view.
            ActingScopeTestSupport.globalSuperAdmin(UUID.randomUUID());
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
            // Every hospital's announcements: a super-admin in global view.
            ActingScopeTestSupport.globalSuperAdmin(UUID.randomUUID());
            when(announcementRepository.findAll()).thenReturn(List.of());

            List<AnnouncementResponseDTO> result = service.getAnnouncements(5);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("returns all when limit exceeds total count")
        void limitExceedsCount() {
            // Every hospital's announcements: a super-admin in global view.
            ActingScopeTestSupport.globalSuperAdmin(UUID.randomUUID());
            Announcement a = buildAnnouncement(UUID.randomUUID(), "Only one", LocalDateTime.now());
            when(announcementRepository.findAll()).thenReturn(List.of(a));

            List<AnnouncementResponseDTO> result = service.getAnnouncements(100);

            assertEquals(1, result.size());
            assertEquals("Only one", result.get(0).getText());
        }

        @Test
        @DisplayName("a caller with no hospital to act at reads no announcements, never every hospital's")
        void noHospitalReadsNothing() {
            HospitalContextHolder.setContext(HospitalContext.builder()
                .permittedHospitalIds(java.util.Set.of(UUID.randomUUID(), UUID.randomUUID()))
                .scopeRefusal(com.example.hms.security.tenant.ActingScope.Reason.AMBIGUOUS)
                .build());

            assertTrue(service.getAnnouncements(5).isEmpty());
            org.mockito.Mockito.verifyNoInteractions(announcementRepository);
        }

        @Test
        @DisplayName("uses hospital-scoped repository when context has active hospital")
        void usesHospitalScopeWhenPresent() {
            UUID hospitalId = UUID.randomUUID();
            Announcement a = buildAnnouncement(UUID.randomUUID(), "Scoped", LocalDateTime.now());
            HospitalContextHolder.setContext(HospitalContext.builder().activeHospitalId(hospitalId).build());
            when(announcementRepository.findByHospital_IdOrderByDateDesc(eq(hospitalId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(a)));

            List<AnnouncementResponseDTO> result = service.getAnnouncements(5);

            assertEquals(1, result.size());
            assertEquals("Scoped", result.get(0).getText());
            verify(announcementRepository).findByHospital_IdOrderByDateDesc(eq(hospitalId), any(Pageable.class));
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

        private void ofHospital(UUID id, UUID hospitalId) {
            com.example.hms.model.Hospital hospital = new com.example.hms.model.Hospital();
            hospital.setId(hospitalId);
            Announcement announcement = buildAnnouncement(id, "Important", LocalDateTime.now());
            announcement.setHospital(hospital);
            when(announcementRepository.findById(id)).thenReturn(Optional.of(announcement));
        }

        private void actingAt(UUID hospitalId) {
            HospitalContextHolder.setContext(HospitalContext.builder()
                .activeHospitalId(hospitalId).permittedHospitalIds(java.util.Set.of(hospitalId)).build());
        }

        @Test
        @DisplayName("hospital A gets 404 for hospital B's announcement, and reads its own")
        void anotherHospitalsAnnouncementIsNotFound() {
            UUID hospitalA = UUID.randomUUID();
            UUID theirs = UUID.randomUUID();
            UUID ours = UUID.randomUUID();
            ofHospital(theirs, UUID.randomUUID());
            ofHospital(ours, hospitalA);
            actingAt(hospitalA);

            assertThrows(com.example.hms.exception.ResourceNotFoundException.class,
                () -> service.getAnnouncement(theirs));
            assertEquals(ours, service.getAnnouncement(ours).getId());
        }

        @Test
        @DisplayName("a caller with no hospital to act at gets 404 for a hospital's announcement, not a refusal")
        void noHospitalToActAtIsNotFound() {
            UUID id = UUID.randomUUID();
            ofHospital(id, UUID.randomUUID());
            HospitalContextHolder.setContext(HospitalContext.builder()
                .permittedHospitalIds(java.util.Set.of(UUID.randomUUID(), UUID.randomUUID())).build());

            assertThrows(com.example.hms.exception.ResourceNotFoundException.class,
                () -> service.getAnnouncement(id));
        }

        @Test
        @DisplayName("a platform-wide announcement (no hospital) stays readable from any hospital")
        void aPlatformAnnouncementStaysReadable() {
            UUID id = UUID.randomUUID();
            when(announcementRepository.findById(id))
                .thenReturn(Optional.of(buildAnnouncement(id, "Maintenance", LocalDateTime.now())));
            actingAt(UUID.randomUUID());

            assertEquals(id, service.getAnnouncement(id).getId());
        }

        @Test
        @DisplayName("a super-admin in global view reads any hospital's")
        void globalViewReadsAny() {
            UUID id = UUID.randomUUID();
            ofHospital(id, UUID.randomUUID());
            HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());

            assertEquals(id, service.getAnnouncement(id).getId());
        }

        @Test
        @DisplayName("QA D1437: an unknown id is a 404, never a bare RuntimeException (500)")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(announcementRepository.findById(id)).thenReturn(Optional.empty());

            assertThrows(com.example.hms.exception.ResourceNotFoundException.class,
                () -> service.getAnnouncement(id));
        }
    }

    // ═══════════════ createAnnouncement ═══════════════

    @Nested
    @DisplayName("createAnnouncement")
    class CreateAnnouncement {

        @Test
        @DisplayName("saves new announcement and returns DTO")
        void createsAndReturns() {
            // Every hospital's announcements: a super-admin in global view.
            ActingScopeTestSupport.globalSuperAdmin(UUID.randomUUID());
            UUID savedId = UUID.randomUUID();
            ArgumentCaptor<Announcement> captor = ArgumentCaptor.forClass(Announcement.class);

            when(announcementRepository.save(any(Announcement.class))).thenAnswer(inv -> {
                Announcement a = inv.getArgument(0);
                a.setId(savedId);
                return a;
            });
            when(assignmentRepository.findLiveStaffUsersPlatformWide()).thenReturn(List.of());

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

        @Test
        @DisplayName("QA D983: a platform-wide announcement notifies staff with the ANNOUNCEMENT type")
        void notifiesStaffWithItsType() {
            ActingScopeTestSupport.globalSuperAdmin(UUID.randomUUID());
            when(announcementRepository.save(any(Announcement.class))).thenAnswer(inv -> inv.getArgument(0));
            when(assignmentRepository.findLiveStaffUsersPlatformWide())
                .thenReturn(List.of(User.builder().username("nurse1").build()));

            service.createAnnouncement("Staff meeting");

            verify(notificationService).createNotification(eq("announcement.new.notification"), any(),
                eq("nurse1"), eq("ANNOUNCEMENT"));
            verify(assignmentRepository, never()).findLiveStaffUsersAtHospital(any());
        }

        @Test
        @DisplayName("QA D1610: hospital A's announcement reaches A's staff and nobody at B")
        void hospitalAnnouncementReachesOnlyItsStaff() {
            UUID hospitalA = UUID.randomUUID();
            Hospital hospital = Hospital.builder().build();
            hospital.setId(hospitalA);
            HospitalContextHolder.setContext(HospitalContext.builder()
                .activeHospitalId(hospitalA).permittedHospitalIds(java.util.Set.of(hospitalA)).build());
            when(hospitalRepository.getReferenceById(hospitalA)).thenReturn(hospital);
            when(announcementRepository.save(any(Announcement.class))).thenAnswer(inv -> inv.getArgument(0));
            when(assignmentRepository.findLiveStaffUsersAtHospital(hospitalA)).thenReturn(List.of(
                User.builder().username("nurse-a").build(), User.builder().username("doctor-a").build()));

            service.createAnnouncement("Coupure d'electricite samedi");

            verify(notificationService).createNotification(eq("announcement.new.notification"), any(),
                eq("nurse-a"), eq("ANNOUNCEMENT"));
            verify(notificationService).createNotification(eq("announcement.new.notification"), any(),
                eq("doctor-a"), eq("ANNOUNCEMENT"));
            verify(notificationService, org.mockito.Mockito.times(2))
                .createNotification(any(), any(), any(String.class), any());
            // Never the platform-wide audience: B's staff are not even read.
            verify(assignmentRepository, never()).findLiveStaffUsersPlatformWide();
            org.mockito.Mockito.verifyNoMoreInteractions(notificationService);
        }

        @Test
        @DisplayName("QA D1615: a blank text is refused naming the field, and nothing is stored or sent")
        void blankTextIsRefused() {
            UUID hospitalA = UUID.randomUUID();
            HospitalContextHolder.setContext(HospitalContext.builder()
                .activeHospitalId(hospitalA).permittedHospitalIds(java.util.Set.of(hospitalA)).build());

            for (String blank : new String[] {null, "", "   "}) {
                com.example.hms.exception.FieldBusinessException ex = assertThrows(
                    com.example.hms.exception.FieldBusinessException.class, () -> service.createAnnouncement(blank));
                assertEquals("text", ex.getField());
            }
            org.mockito.Mockito.verifyNoInteractions(announcementRepository, notificationService, assignmentRepository);
        }

        @Test
        @DisplayName("without a hospital, only a super-admin in global view posts a platform-wide announcement")
        void noHospitalCannotPostPlatformWide() {
            assertThrows(com.example.hms.exception.BusinessException.class,
                () -> service.createAnnouncement("Everyone, everywhere"));
            org.mockito.Mockito.verifyNoInteractions(announcementRepository);
        }

        @Test
        @DisplayName("assigns current hospital when context has active hospital")
        void assignsCurrentHospital() {
            UUID hospitalId = UUID.randomUUID();
            UUID savedId = UUID.randomUUID();
            Hospital hospital = Hospital.builder().build();
            hospital.setId(hospitalId);
            ArgumentCaptor<Announcement> captor = ArgumentCaptor.forClass(Announcement.class);

            HospitalContextHolder.setContext(HospitalContext.builder().activeHospitalId(hospitalId).build());
            when(hospitalRepository.getReferenceById(hospitalId)).thenReturn(hospital);
            when(announcementRepository.save(any(Announcement.class))).thenAnswer(inv -> {
                Announcement a = inv.getArgument(0);
                a.setId(savedId);
                return a;
            });
            when(assignmentRepository.findLiveStaffUsersAtHospital(hospitalId)).thenReturn(List.of());

            service.createAnnouncement("Scoped policy");

            verify(announcementRepository).save(captor.capture());
            assertEquals(hospitalId, captor.getValue().getHospital().getId());
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
        @DisplayName("QA D1437: an unknown id is a 404, never a bare RuntimeException (500)")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(announcementRepository.findById(id)).thenReturn(Optional.empty());

            assertThrows(com.example.hms.exception.ResourceNotFoundException.class,
                () -> service.updateAnnouncement(id, "text"));
        }

        @Test
        @DisplayName("QA D1615: an edit to a blank text is refused naming the field, and the text is kept")
        void blankEditIsRefused() {
            UUID id = UUID.randomUUID();
            Announcement existing = buildAnnouncement(id, "Old text", LocalDateTime.now());
            when(announcementRepository.findById(id)).thenReturn(Optional.of(existing));

            com.example.hms.exception.FieldBusinessException ex = assertThrows(
                com.example.hms.exception.FieldBusinessException.class, () -> service.updateAnnouncement(id, " "));
            assertEquals("text", ex.getField());
            assertEquals("Old text", existing.getText());
            verify(announcementRepository, never()).save(any(Announcement.class));
        }
    }

    // ═══════════════ deleteAnnouncement ═══════════════

    @Nested
    @DisplayName("deleteAnnouncement")
    class DeleteAnnouncement {

        @Test
        @DisplayName("loads the announcement, then deletes it")
        void deletesById() {
            UUID id = UUID.randomUUID();
            Announcement announcement = new Announcement();
            when(announcementRepository.findById(id)).thenReturn(Optional.of(announcement));

            service.deleteAnnouncement(id);

            verify(announcementRepository).delete(announcement);
        }

        @Test
        @DisplayName("QA r5 tenancy: another hospital's announcement answers as unknown and is kept")
        void refusesAnotherHospitalsAnnouncement() {
            UUID id = UUID.randomUUID();
            com.example.hms.model.Hospital hospitalA = new com.example.hms.model.Hospital();
            hospitalA.setId(UUID.randomUUID());
            Announcement announcement = new Announcement();
            announcement.setHospital(hospitalA);
            when(announcementRepository.findById(id)).thenReturn(Optional.of(announcement));
            UUID hospitalB = UUID.randomUUID();
            com.example.hms.security.context.HospitalContextHolder.setContext(
                com.example.hms.security.context.HospitalContext.builder()
                    .activeHospitalId(hospitalB).permittedHospitalIds(java.util.Set.of(hospitalB)).build());
            try {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.deleteAnnouncement(id))
                    .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
                verify(announcementRepository, never()).delete(any(Announcement.class));
            } finally {
                com.example.hms.security.context.HospitalContextHolder.clear();
            }
        }
    }
}
