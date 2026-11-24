package com.example.hms.service.notification;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import com.example.hms.model.NotificationPreference;
import com.example.hms.repository.NotificationPreferenceRepository;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PatientConsentDecisionTest {
    @Test
    void aDecisionCopiesTheRefusalBeforeTheEntityCanBeChanged() {
        var preferences = mock(NotificationPreferenceRepository.class);
        var account = UUID.randomUUID();
        var author = UUID.randomUUID();
        var at = LocalDateTime.of(2026, 11, 23, 8, 0);
        var choice = NotificationPreference.builder().channel(NotificationChannel.SMS).build();
        choice.recordChoice(false, author, at);
        when(preferences.findByUser_IdAndNotificationType(account, NotificationType.LAB_RESULT))
            .thenReturn(List.of(choice));
        var decision = new PatientChannelConsent(preferences)
            .decision(account, NotificationType.LAB_RESULT, NotificationChannel.SMS);
        choice.recordChoice(true, UUID.randomUUID(), at.plusMinutes(1));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.recordedBy()).isEqualTo(author);
        assertThat(decision.recordedAt()).isEqualTo(at);
        assertThat(decision.scopeTo(account)).isEqualTo(decision);
        assertThat(decision.scopeTo(UUID.randomUUID()).recordedBy()).isNull();
        assertThat(decision.scopeTo(null).recordedAt()).isNull();
        verify(preferences).findByUser_IdAndNotificationType(account, NotificationType.LAB_RESULT);
        verifyNoMoreInteractions(preferences);
    }
}
