package com.example.hms.service.notification;

import com.example.hms.model.NotificationPreference;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NotificationPreferenceViewsTest {
    @Test
    void recorderNamesAreBatchedAndNeverFallBackToContactIdentifiers() {
        var users = mock(UserRepository.class);
        var id = UUID.randomUUID();
        var name = mock(UserRepository.ClinicalActorName.class);
        when(name.getId()).thenReturn(id);
        when(name.getFirstName()).thenReturn(" cdx ");
        when(name.getLastName()).thenReturn("Recorder");
        when(users.findClinicalActorNames(List.of(id))).thenReturn(List.of(name));
        var preference = NotificationPreference.builder().build();
        var at = LocalDateTime.of(2026, 11, 22, 8, 0);
        preference.recordChoice(false, id, at);
        var dto = NotificationPreferenceViews.of(preference);
        NotificationPreferenceViews.describe(List.of(dto), users);
        assertThat(dto.getRecordedByName()).isEqualTo("cdx Recorder");
        assertThat(dto.getRecordedAt()).isEqualTo(at);
        verify(users).findClinicalActorNames(List.of(id));
        verify(name, never()).getUsername();
    }

    @Test
    void legacyChoicesRemainHonestlyUnattributedWithoutAUserLookup() {
        var users = mock(UserRepository.class);
        var dto = NotificationPreferenceViews.of(NotificationPreference.builder().enabled(false).build());
        NotificationPreferenceViews.describe(List.of(dto), users);
        assertThat(dto.getRecordedAt()).isNull();
        assertThat(dto.getRecordedByUserId()).isNull();
        assertThat(dto.getRecordedByName()).isNull();
        verifyNoInteractions(users);
    }
}
