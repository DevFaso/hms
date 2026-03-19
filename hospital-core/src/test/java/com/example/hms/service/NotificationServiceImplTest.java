package com.example.hms.service;

import com.example.hms.controller.NotificationWebSocketController;
import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Notification;
import com.example.hms.model.NotificationPreference;
import com.example.hms.model.User;
import com.example.hms.payload.dto.portal.NotificationPreferenceDTO;
import com.example.hms.payload.dto.portal.NotificationPreferenceUpdateDTO;
import com.example.hms.repository.NotificationPreferenceRepository;
import com.example.hms.repository.NotificationRepository;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationWebSocketController notificationWebSocketController;

    @Mock
    private NotificationPreferenceRepository notificationPreferenceRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private NotificationServiceImpl service;

    private Notification sampleNotification;
    private final UUID notificationId = UUID.randomUUID();
    private final String username = "john.doe";

    @BeforeEach
    void setUp() {
        sampleNotification = Notification.builder()
                .id(notificationId)
                .message("Test message")
                .recipientUsername(username)
                .createdAt(LocalDateTime.now())
                .read(false)
                .build();
    }

    // ── getNotificationsForUser(String) ──────────────────────────────────────

    @Nested
    @DisplayName("getNotificationsForUser(String username)")
    class GetNotificationsForUserLegacy {

        @Test
        @DisplayName("returns all notifications for user (legacy unpaged)")
        void returnsAllNotifications() {
            Notification n1 = Notification.builder().id(UUID.randomUUID()).message("msg1")
                    .recipientUsername(username).createdAt(LocalDateTime.now()).read(false).build();
            Notification n2 = Notification.builder().id(UUID.randomUUID()).message("msg2")
                    .recipientUsername(username).createdAt(LocalDateTime.now()).read(true).build();

            Page<Notification> page = new PageImpl<>(List.of(n1, n2));
            when(notificationRepository.findByRecipientUsername(eq(username), any(Pageable.class)))
                    .thenReturn(page);

            List<Notification> result = service.getNotificationsForUser(username);

            assertThat(result).hasSize(2).containsExactly(n1, n2);
            verify(notificationRepository).findByRecipientUsername(eq(username), any(Pageable.class));
        }

        @Test
        @DisplayName("returns empty list when no notifications exist")
        void returnsEmptyList() {
            Page<Notification> emptyPage = new PageImpl<>(List.of());
            when(notificationRepository.findByRecipientUsername(eq(username), any(Pageable.class)))
                    .thenReturn(emptyPage);

            List<Notification> result = service.getNotificationsForUser(username);

            assertThat(result).isEmpty();
        }
    }

    // ── getNotificationsForUser(String, Boolean, String, Pageable) ───────────

    @Nested
    @DisplayName("getNotificationsForUser(String, Boolean, String, Pageable)")
    class GetNotificationsForUserPaged {

        private final Pageable pageable = PageRequest.of(0, 10);

        @Test
        @DisplayName("filters by read AND search when both provided")
        void filtersByReadAndSearch() {
            Page<Notification> page = new PageImpl<>(List.of(sampleNotification));
            when(notificationRepository.findByRecipientUsernameAndReadAndMessageContainingIgnoreCase(
                    username, true, "urgent", pageable))
                    .thenReturn(page);

            Page<Notification> result = service.getNotificationsForUser(username, true, "urgent", pageable);

            assertThat(result.getContent()).hasSize(1);
            verify(notificationRepository)
                    .findByRecipientUsernameAndReadAndMessageContainingIgnoreCase(username, true, "urgent", pageable);
        }

        @Test
        @DisplayName("filters by read only when search is null")
        void filtersByReadOnly_searchNull() {
            Page<Notification> page = new PageImpl<>(List.of(sampleNotification));
            when(notificationRepository.findByRecipientUsernameAndRead(
                    username, false, pageable))
                    .thenReturn(page);

            Page<Notification> result = service.getNotificationsForUser(username, false, null, pageable);

            assertThat(result.getContent()).hasSize(1);
            verify(notificationRepository).findByRecipientUsernameAndRead(username, false, pageable);
        }

        @Test
        @DisplayName("filters by read only when search is empty string")
        void filtersByReadOnly_searchEmpty() {
            Page<Notification> page = new PageImpl<>(List.of(sampleNotification));
            when(notificationRepository.findByRecipientUsernameAndRead(
                    username, true, pageable))
                    .thenReturn(page);

            Page<Notification> result = service.getNotificationsForUser(username, true, "", pageable);

            assertThat(result.getContent()).hasSize(1);
            verify(notificationRepository).findByRecipientUsernameAndRead(username, true, pageable);
        }

        @Test
        @DisplayName("filters by search only when read is null and search is non-empty")
        void filtersBySearchOnly() {
            Page<Notification> page = new PageImpl<>(List.of(sampleNotification));
            when(notificationRepository.findByRecipientUsernameAndMessageContainingIgnoreCase(
                    username, "lab", pageable))
                    .thenReturn(page);

            Page<Notification> result = service.getNotificationsForUser(username, null, "lab", pageable);

            assertThat(result.getContent()).hasSize(1);
            verify(notificationRepository)
                    .findByRecipientUsernameAndMessageContainingIgnoreCase(username, "lab", pageable);
        }

        @Test
        @DisplayName("returns all (no filter) when read is null and search is null")
        void noFilter_bothNull() {
            Page<Notification> page = new PageImpl<>(List.of(sampleNotification));
            when(notificationRepository.findByRecipientUsername(username, pageable))
                    .thenReturn(page);

            Page<Notification> result = service.getNotificationsForUser(username, null, null, pageable);

            assertThat(result.getContent()).hasSize(1);
            verify(notificationRepository).findByRecipientUsername(username, pageable);
        }

        @Test
        @DisplayName("returns all (no filter) when read is null and search is empty")
        void noFilter_readNull_searchEmpty() {
            Page<Notification> page = new PageImpl<>(List.of(sampleNotification));
            when(notificationRepository.findByRecipientUsername(username, pageable))
                    .thenReturn(page);

            Page<Notification> result = service.getNotificationsForUser(username, null, "", pageable);

            assertThat(result.getContent()).hasSize(1);
            verify(notificationRepository).findByRecipientUsername(username, pageable);
        }
    }

    // ── createNotification ───────────────────────────────────────────────────

    @Nested
    @DisplayName("createNotification")
    class CreateNotification {

        @Test
        @DisplayName("builds notification, saves, sends via WebSocket, and returns saved entity")
        void createsAndSendsNotification() {
            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            when(notificationRepository.save(captor.capture())).thenReturn(sampleNotification);

            Notification result = service.createNotification("Hello", username);

            assertThat(result).isSameAs(sampleNotification);

            Notification captured = captor.getValue();
            assertThat(captured.getMessage()).isEqualTo("Hello");
            assertThat(captured.getRecipientUsername()).isEqualTo(username);
            assertThat(captured.isRead()).isFalse();
            assertThat(captured.getCreatedAt()).isNotNull();

            verify(notificationRepository).save(any(Notification.class));
            verify(notificationWebSocketController).sendNotification(sampleNotification);
        }
    }

    // ── markAsRead ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("markAsRead")
    class MarkAsRead {

        @Test
        @DisplayName("marks notification as read when found")
        void marksAsReadWhenFound() {
            Notification notification = Notification.builder()
                    .id(notificationId)
                    .message("msg")
                    .recipientUsername(username)
                    .createdAt(LocalDateTime.now())
                    .read(false)
                    .build();

            when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));

            service.markAsRead(notificationId);

            assertThat(notification.isRead()).isTrue();
            verify(notificationRepository).save(notification);
        }

        @Test
        @DisplayName("does nothing when notification not found")
        void doesNothingWhenNotFound() {
            when(notificationRepository.findById(notificationId)).thenReturn(Optional.empty());

            service.markAsRead(notificationId);

            verify(notificationRepository, never()).save(any());
        }
    }

    // ── getPreferences ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("getPreferences")
    class GetPreferences {

        @Test
        @DisplayName("returns mapped DTOs from repository")
        void returnsMappedDTOs() {
            UUID userId = UUID.randomUUID();
            NotificationPreference pref = NotificationPreference.builder()
                    .notificationType(NotificationType.APPOINTMENT_REMINDER)
                    .channel(NotificationChannel.EMAIL)
                    .enabled(true)
                    .build();
            pref.setId(UUID.randomUUID());

            when(notificationPreferenceRepository.findByUser_Id(userId))
                    .thenReturn(List.of(pref));

            List<NotificationPreferenceDTO> result = service.getPreferences(userId);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getNotificationType()).isEqualTo(NotificationType.APPOINTMENT_REMINDER);
            assertThat(result.get(0).getChannel()).isEqualTo(NotificationChannel.EMAIL);
            assertThat(result.get(0).isEnabled()).isTrue();
        }

        @Test
        @DisplayName("returns empty list when no preferences exist")
        void returnsEmptyWhenNone() {
            UUID userId = UUID.randomUUID();
            when(notificationPreferenceRepository.findByUser_Id(userId))
                    .thenReturn(List.of());

            List<NotificationPreferenceDTO> result = service.getPreferences(userId);

            assertThat(result).isEmpty();
        }
    }

    // ── updatePreferences ────────────────────────────────────────────────────

    @Nested
    @DisplayName("updatePreferences")
    class UpdatePreferences {

        @Test
        @DisplayName("deletes old prefs, saves new, and returns DTOs")
        void deletesOldAndSavesNew() {
            UUID userId = UUID.randomUUID();
            User user = new User();
            user.setId(userId);

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            NotificationPreferenceUpdateDTO update = new NotificationPreferenceUpdateDTO();
            update.setNotificationType(NotificationType.LAB_RESULT);
            update.setChannel(NotificationChannel.SMS);
            update.setEnabled(true);

            NotificationPreference saved = NotificationPreference.builder()
                    .user(user)
                    .notificationType(NotificationType.LAB_RESULT)
                    .channel(NotificationChannel.SMS)
                    .enabled(true)
                    .build();
            saved.setId(UUID.randomUUID());

            when(notificationPreferenceRepository.saveAll(any()))
                    .thenReturn(List.of(saved));

            List<NotificationPreferenceDTO> result = service.updatePreferences(userId, List.of(update));

            verify(notificationPreferenceRepository).deleteByUser_Id(userId);
            verify(notificationPreferenceRepository).flush();
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getNotificationType()).isEqualTo(NotificationType.LAB_RESULT);
        }

        @Test
        @DisplayName("throws when user not found")
        void throwsWhenUserNotFound() {
            UUID userId = UUID.randomUUID();
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            List<NotificationPreferenceUpdateDTO> emptyList = List.of();
            assertThatThrownBy(() -> service.updatePreferences(userId, emptyList))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
