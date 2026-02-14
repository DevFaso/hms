package com.example.hms.service;

import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
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
import java.util.UUID;
import java.util.stream.StreamSupport;

import static com.example.hms.config.PasswordRotationPolicy.MAX_PASSWORD_AGE_DAYS;
import static com.example.hms.config.PasswordRotationPolicy.WARNING_WINDOW_DAYS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordRotationSchedulerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private PasswordRotationScheduler scheduler;

    // ─── helpers ───

    private User buildUser(String email, LocalDateTime passwordChangedAt) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(email.split("@")[0]);
        user.setEmail(email);
        user.setActive(true);
        user.setDeleted(false);
        user.setPasswordChangedAt(passwordChangedAt);
        user.setCreatedAt(passwordChangedAt != null ? passwordChangedAt.minusDays(5) : null);
        user.setUpdatedAt(passwordChangedAt != null ? passwordChangedAt.minusDays(1) : null);
        user.setForcePasswordChange(false);
        user.setPasswordRotationWarningAt(null);
        user.setPasswordRotationForcedAt(null);
        return user;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Iterable<User>> captureIterable() {
        return ArgumentCaptor.forClass(Iterable.class);
    }

    private List<User> capturedUsers() {
        ArgumentCaptor<Iterable<User>> captor = captureIterable();
        verify(userRepository).saveAll(captor.capture());
        return StreamSupport.stream(captor.getValue().spliterator(), false).toList();
    }

    // ═══════════════ empty / no-eligible ═══════════════

    @Nested
    @DisplayName("No-op scenarios")
    class NoOp {

        @Test
        @DisplayName("does nothing when user list is empty")
        void emptyUserList() {
            when(userRepository.findByIsDeletedFalse()).thenReturn(List.of());

            scheduler.process(LocalDateTime.now());

            verify(userRepository, never()).saveAll(any());
            verifyNoInteractions(emailService);
        }

        @Test
        @DisplayName("skips inactive user")
        void inactiveUser() {
            LocalDateTime now = LocalDateTime.of(2025, 6, 1, 6, 0);
            User user = buildUser("inactive@example.com", now.minusDays(MAX_PASSWORD_AGE_DAYS + 10));
            user.setActive(false);

            when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));

            scheduler.process(now);

            verify(userRepository, never()).saveAll(any());
            verifyNoInteractions(emailService);
        }

        @Test
        @DisplayName("skips user with null email")
        void nullEmail() {
            LocalDateTime now = LocalDateTime.of(2025, 6, 1, 6, 0);
            User user = buildUser("test@example.com", now.minusDays(MAX_PASSWORD_AGE_DAYS + 5));
            user.setEmail(null);

            when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));

            scheduler.process(now);

            verify(userRepository, never()).saveAll(any());
            verifyNoInteractions(emailService);
        }

        @Test
        @DisplayName("skips user with blank email")
        void blankEmail() {
            LocalDateTime now = LocalDateTime.of(2025, 6, 1, 6, 0);
            User user = buildUser("test@example.com", now.minusDays(MAX_PASSWORD_AGE_DAYS + 5));
            user.setEmail("   ");

            when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));

            scheduler.process(now);

            verify(userRepository, never()).saveAll(any());
            verifyNoInteractions(emailService);
        }

        @Test
        @DisplayName("no action for user outside warning window with no stale warning")
        void outsideWarningWindow() {
            LocalDateTime now = LocalDateTime.of(2025, 6, 1, 6, 0);
            // password changed recently, daysUntilDue > WARNING_WINDOW_DAYS
            User user = buildUser("fresh@example.com", now.minusDays(5));

            when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));

            scheduler.process(now);

            verify(userRepository, never()).saveAll(any());
            verifyNoInteractions(emailService);
        }
    }

    // ═══════════════ Reminder scenarios ═══════════════

    @Nested
    @DisplayName("Reminder scenarios")
    class Reminders {

        @Test
        @DisplayName("sends reminder when within warning window")
        void withinWarningWindow() {
            LocalDateTime now = LocalDateTime.of(2025, 1, 1, 6, 0);
            User user = buildUser("alex@example.com", now.minusDays(MAX_PASSWORD_AGE_DAYS - 10));

            when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));

            scheduler.process(now);

            List<User> persisted = capturedUsers();
            assertThat(persisted).containsExactly(user);
            assertThat(user.getPasswordRotationWarningAt()).isEqualTo(now);
            assertThat(user.isForcePasswordChange()).isFalse();

            verify(emailService).sendPasswordRotationReminderEmail(eq(user.getEmail()), anyString(), eq(10L), any());
            verify(emailService, never()).sendPasswordRotationForceChangeEmail(any(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("skips reminder when already warned today")
        void alreadyWarnedToday() {
            LocalDateTime now = LocalDateTime.of(2025, 1, 1, 6, 0);
            User user = buildUser("warned@example.com", now.minusDays(MAX_PASSWORD_AGE_DAYS - 10));
            user.setPasswordRotationWarningAt(now.withHour(3)); // warned earlier today

            when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));

            scheduler.process(now);

            verify(userRepository, never()).saveAll(any());
            verifyNoInteractions(emailService);
        }

        @Test
        @DisplayName("reminder email failure does not prevent state persistence")
        void reminderEmailFailure() {
            LocalDateTime now = LocalDateTime.of(2025, 1, 1, 6, 0);
            User user = buildUser("fail@example.com", now.minusDays(MAX_PASSWORD_AGE_DAYS - 5));

            doThrow(new RuntimeException("SMTP error")).when(emailService)
                    .sendPasswordRotationReminderEmail(any(), any(), anyLong(), any());

            when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));

            scheduler.process(now);

            // State should still be saved even though email failed (caught inside sendReminderEmail)
            List<User> persisted = capturedUsers();
            assertThat(persisted).containsExactly(user);
            assertThat(user.getPasswordRotationWarningAt()).isEqualTo(now);
        }
    }

    // ═══════════════ Enforcement (overdue) ═══════════════

    @Nested
    @DisplayName("Enforcement scenarios")
    class Enforcement {

        @Test
        @DisplayName("forces password change when expired")
        void expired() {
            LocalDateTime now = LocalDateTime.of(2025, 1, 1, 6, 0);
            User user = buildUser("expired@example.com", now.minusDays(MAX_PASSWORD_AGE_DAYS + 2));

            when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));

            scheduler.process(now);

            List<User> persisted = capturedUsers();
            assertThat(persisted).containsExactly(user);
            assertThat(user.isForcePasswordChange()).isTrue();
            assertThat(user.getPasswordRotationForcedAt()).isEqualTo(now);
            assertThat(user.getPasswordRotationWarningAt()).isNull();

            verify(emailService).sendPasswordRotationForceChangeEmail(eq(user.getEmail()), anyString(), any(), eq(2L));
            verify(emailService, never()).sendPasswordRotationReminderEmail(any(), any(), anyLong(), any());
        }

        @Test
        @DisplayName("does not re-send enforcement email when already forced today")
        void alreadyForcedToday() {
            LocalDateTime now = LocalDateTime.of(2025, 1, 1, 6, 0);
            User user = buildUser("forced@example.com", now.minusDays(MAX_PASSWORD_AGE_DAYS + 3));
            user.setForcePasswordChange(true);
            user.setPasswordRotationForcedAt(now.withHour(2)); // forced earlier today

            when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));

            scheduler.process(now);

            // already forced today → forcedToday=true → no email, no state change
            verify(userRepository, never()).saveAll(any());
            verifyNoInteractions(emailService);
        }

        @Test
        @DisplayName("re-sends enforcement email on new day even if already force=true")
        void newDayReenforces() {
            LocalDateTime now = LocalDateTime.of(2025, 1, 2, 6, 0);
            User user = buildUser("re-enforce@example.com", now.minusDays(MAX_PASSWORD_AGE_DAYS + 5));
            user.setForcePasswordChange(true);
            user.setPasswordRotationForcedAt(now.minusDays(1)); // forced yesterday, not today

            when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));

            scheduler.process(now);

            List<User> persisted = capturedUsers();
            assertThat(persisted).containsExactly(user);
            assertThat(user.getPasswordRotationForcedAt()).isEqualTo(now);

            verify(emailService).sendPasswordRotationForceChangeEmail(eq(user.getEmail()), anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("enforcement email failure does not prevent state persistence")
        void forceEmailFailure() {
            LocalDateTime now = LocalDateTime.of(2025, 1, 1, 6, 0);
            User user = buildUser("forcefail@example.com", now.minusDays(MAX_PASSWORD_AGE_DAYS + 1));

            doThrow(new RuntimeException("SMTP down")).when(emailService)
                    .sendPasswordRotationForceChangeEmail(any(), any(), any(), anyLong());

            when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));

            scheduler.process(now);

            List<User> persisted = capturedUsers();
            assertThat(persisted).containsExactly(user);
            assertThat(user.isForcePasswordChange()).isTrue();
        }

        @Test
        @DisplayName("enforcement at exactly due date (daysUntilDue == 0)")
        void exactlyDueDate() {
            LocalDateTime now = LocalDateTime.of(2025, 6, 1, 6, 0);
            // password changed exactly MAX_PASSWORD_AGE_DAYS ago → daysUntilDue = 0 → enforcement
            User user = buildUser("exact@example.com", now.minusDays(MAX_PASSWORD_AGE_DAYS));

            when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));

            scheduler.process(now);

            List<User> persisted = capturedUsers();
            assertThat(persisted).containsExactly(user);
            assertThat(user.isForcePasswordChange()).isTrue();
            verify(emailService).sendPasswordRotationForceChangeEmail(any(), any(), any(), anyLong());
        }
    }

    // ═══════════════ clearStaleWarning ═══════════════

    @Nested
    @DisplayName("Clear stale warning")
    class ClearStaleWarning {

        @Test
        @DisplayName("clears stale warning when outside warning window")
        void clearsStaleWarning() {
            LocalDateTime now = LocalDateTime.of(2025, 6, 1, 6, 0);
            // Password changed very recently → well outside warning window
            User user = buildUser("stale@example.com", now.minusDays(2));
            // But has a leftover warning from a previous cycle
            user.setPasswordRotationWarningAt(now.minusDays(30));

            when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));

            scheduler.process(now);

            List<User> persisted = capturedUsers();
            assertThat(persisted).containsExactly(user);
            assertThat(user.getPasswordRotationWarningAt()).isNull(); // cleared
            verifyNoInteractions(emailService);
        }
    }

    // ═══════════════ resolveEffectiveChangedAt fallback chain ═══════════════

    @Nested
    @DisplayName("resolveEffectiveChangedAt fallbacks")
    class ResolveEffectiveChangedAt {

        @Test
        @DisplayName("falls back to passwordRotationForcedAt when passwordChangedAt is null")
        void fallbackToForcedAt() {
            LocalDateTime now = LocalDateTime.of(2025, 6, 1, 6, 0);
            User user = buildUser("fallback@example.com", null);
            user.setPasswordChangedAt(null);
            user.setPasswordRotationForcedAt(now.minusDays(MAX_PASSWORD_AGE_DAYS - 5));
            user.setCreatedAt(null);
            user.setUpdatedAt(null);

            when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));

            scheduler.process(now);

            // daysUntilDue = 5 → within WARNING_WINDOW_DAYS → reminder sent
            verify(emailService).sendPasswordRotationReminderEmail(eq(user.getEmail()), anyString(), eq(5L), any());
        }

        @Test
        @DisplayName("falls back to createdAt")
        void fallbackToCreatedAt() {
            LocalDateTime now = LocalDateTime.of(2025, 6, 1, 6, 0);
            User user = buildUser("created@example.com", null);
            user.setPasswordChangedAt(null);
            user.setPasswordRotationForcedAt(null);
            user.setCreatedAt(now.minusDays(MAX_PASSWORD_AGE_DAYS + 1));
            user.setUpdatedAt(null);

            when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));

            scheduler.process(now);

            // overdue → enforcement
            assertThat(user.isForcePasswordChange()).isTrue();
            verify(emailService).sendPasswordRotationForceChangeEmail(any(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("falls back to updatedAt")
        void fallbackToUpdatedAt() {
            LocalDateTime now = LocalDateTime.of(2025, 6, 1, 6, 0);
            User user = buildUser("updated@example.com", null);
            user.setPasswordChangedAt(null);
            user.setPasswordRotationForcedAt(null);
            user.setCreatedAt(null);
            user.setUpdatedAt(now.minusDays(MAX_PASSWORD_AGE_DAYS - 10));

            when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));

            scheduler.process(now);

            verify(emailService).sendPasswordRotationReminderEmail(eq(user.getEmail()), anyString(), eq(10L), any());
        }

        @Test
        @DisplayName("falls back to snapshot when all timestamps are null")
        void fallbackToSnapshot() {
            LocalDateTime now = LocalDateTime.of(2025, 6, 1, 6, 0);
            User user = buildUser("snapshot@example.com", null);
            user.setPasswordChangedAt(null);
            user.setPasswordRotationForcedAt(null);
            user.setCreatedAt(null);
            user.setUpdatedAt(null);

            when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));

            scheduler.process(now);

            // effectiveChangedAt = snapshot → dueOn = now + MAX_PASSWORD_AGE_DAYS → way outside window
            verify(userRepository, never()).saveAll(any());
            verifyNoInteractions(emailService);
        }
    }

    // ═══════════════ resolveDisplayName fallbacks ═══════════════

    @Nested
    @DisplayName("resolveDisplayName fallbacks")
    class ResolveDisplayName {

        @Test
        @DisplayName("uses firstName when available")
        void usesFirstName() {
            LocalDateTime now = LocalDateTime.of(2025, 1, 1, 6, 0);
            User user = buildUser("first@example.com", now.minusDays(MAX_PASSWORD_AGE_DAYS - 5));
            user.setFirstName("Alice");
            user.setLastName("Smith");

            when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));

            scheduler.process(now);

            verify(emailService).sendPasswordRotationReminderEmail(eq(user.getEmail()), eq("Alice"), anyLong(), any());
        }

        @Test
        @DisplayName("uses lastName when firstName is blank")
        void usesLastName() {
            LocalDateTime now = LocalDateTime.of(2025, 1, 1, 6, 0);
            User user = buildUser("last@example.com", now.minusDays(MAX_PASSWORD_AGE_DAYS - 5));
            user.setFirstName("  ");
            user.setLastName("Williams");

            when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));

            scheduler.process(now);

            verify(emailService).sendPasswordRotationReminderEmail(eq(user.getEmail()), eq("Williams"), anyLong(), any());
        }

        @Test
        @DisplayName("uses username when first and last names are null")
        void usesUsername() {
            LocalDateTime now = LocalDateTime.of(2025, 1, 1, 6, 0);
            User user = buildUser("user@example.com", now.minusDays(MAX_PASSWORD_AGE_DAYS - 5));
            user.setFirstName(null);
            user.setLastName(null);
            user.setUsername("johnny");

            when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));

            scheduler.process(now);

            verify(emailService).sendPasswordRotationReminderEmail(eq(user.getEmail()), eq("johnny"), anyLong(), any());
        }

        @Test
        @DisplayName("uses 'there' when all name fields are null")
        void usesThere() {
            LocalDateTime now = LocalDateTime.of(2025, 1, 1, 6, 0);
            User user = buildUser("anon@example.com", now.minusDays(MAX_PASSWORD_AGE_DAYS - 5));
            user.setFirstName(null);
            user.setLastName(null);
            user.setUsername(null);

            when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));

            scheduler.process(now);

            verify(emailService).sendPasswordRotationReminderEmail(eq(user.getEmail()), eq("there"), anyLong(), any());
        }

        @Test
        @DisplayName("uses lastName when firstName is null")
        void lastNameWhenFirstNull() {
            LocalDateTime now = LocalDateTime.of(2025, 1, 1, 6, 0);
            User user = buildUser("ln@example.com", now.minusDays(MAX_PASSWORD_AGE_DAYS - 5));
            user.setFirstName(null);
            user.setLastName("Doe");

            when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));

            scheduler.process(now);

            verify(emailService).sendPasswordRotationReminderEmail(eq(user.getEmail()), eq("Doe"), anyLong(), any());
        }
    }

    // ═══════════════ runDailyPasswordRotationCheck ═══════════════

    @Test
    @DisplayName("runDailyPasswordRotationCheck delegates to process()")
    void runDailyDelegates() {
        when(userRepository.findByIsDeletedFalse()).thenReturn(List.of());

        scheduler.runDailyPasswordRotationCheck();

        verify(userRepository).findByIsDeletedFalse();
    }

    // ═══════════════ Multiple users in one batch ═══════════════

    @Test
    @DisplayName("processes multiple users with different states in single run")
    void multipleUsersInBatch() {
        LocalDateTime now = LocalDateTime.of(2025, 6, 1, 6, 0);

        // User 1: within warning window → reminder
        User reminderUser = buildUser("remind@example.com", now.minusDays(MAX_PASSWORD_AGE_DAYS - 10));
        // User 2: expired → enforcement
        User expiredUser = buildUser("expire@example.com", now.minusDays(MAX_PASSWORD_AGE_DAYS + 3));
        // User 3: inactive → skip
        User inactiveUser = buildUser("skip@example.com", now.minusDays(MAX_PASSWORD_AGE_DAYS + 5));
        inactiveUser.setActive(false);

        when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(reminderUser, expiredUser, inactiveUser));

        scheduler.process(now);

        List<User> persisted = capturedUsers();
        assertThat(persisted).containsExactlyInAnyOrder(reminderUser, expiredUser);
        verify(emailService).sendPasswordRotationReminderEmail(eq("remind@example.com"), anyString(), anyLong(), any());
        verify(emailService).sendPasswordRotationForceChangeEmail(eq("expire@example.com"), anyString(), any(), anyLong());
    }
}
