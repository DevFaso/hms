package com.example.hms.service;

import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordRotationSchedulerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private PasswordRotationScheduler scheduler;

    @Test
    void shouldSendReminderWhenWithinWindow() {
        LocalDateTime now = LocalDateTime.of(2025, 1, 1, 6, 0);
        User user = buildUser("alex.reminder@example.com", now.minusDays(MAX_PASSWORD_AGE_DAYS - 10));

        when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));

        scheduler.process(now);

    ArgumentCaptor<Iterable<User>> saveCaptor = captureIterable();
        verify(userRepository).saveAll(saveCaptor.capture());
        List<User> persisted = StreamSupport.stream(saveCaptor.getValue().spliterator(), false).toList();

        assertThat(persisted).containsExactly(user);
        assertThat(user.getPasswordRotationWarningAt()).isEqualTo(now);
        assertThat(user.isForcePasswordChange()).isFalse();

        verify(emailService).sendPasswordRotationReminderEmail(eq(user.getEmail()), anyString(), eq(10L), any());
        verify(emailService, never()).sendPasswordRotationForceChangeEmail(any(), any(), any(), anyLong());
    }

    @Test
    void shouldForcePasswordChangeWhenExpired() {
        LocalDateTime now = LocalDateTime.of(2025, 1, 1, 6, 0);
        User user = buildUser("case.expired@example.com", now.minusDays(MAX_PASSWORD_AGE_DAYS + 2));

        when(userRepository.findByIsDeletedFalse()).thenReturn(List.of(user));

        scheduler.process(now);

    ArgumentCaptor<Iterable<User>> saveCaptor = captureIterable();
        verify(userRepository).saveAll(saveCaptor.capture());
        List<User> persisted = StreamSupport.stream(saveCaptor.getValue().spliterator(), false).toList();

        assertThat(persisted).containsExactly(user);
        assertThat(user.isForcePasswordChange()).isTrue();
        assertThat(user.getPasswordRotationForcedAt()).isEqualTo(now);
        assertThat(user.getPasswordRotationWarningAt()).isNull();

        verify(emailService).sendPasswordRotationForceChangeEmail(eq(user.getEmail()), anyString(), any(), eq(2L));
        verify(emailService, never()).sendPasswordRotationReminderEmail(any(), any(), anyLong(), any());
    }

    private User buildUser(String email, LocalDateTime passwordChangedAt) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(email.split("@")[0]);
        user.setEmail(email);
        user.setActive(true);
        user.setDeleted(false);
        user.setPasswordChangedAt(passwordChangedAt);
        user.setCreatedAt(passwordChangedAt.minusDays(5));
        user.setUpdatedAt(passwordChangedAt.minusDays(1));
        user.setForcePasswordChange(false);
        user.setPasswordRotationWarningAt(null);
        user.setPasswordRotationForcedAt(null);
        return user;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Iterable<User>> captureIterable() {
        return ArgumentCaptor.forClass(Iterable.class);
    }
}
