package com.example.hms.service;

import com.example.hms.model.PasswordHistory;
import com.example.hms.repository.PasswordHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordHistoryServiceTest {

    @Mock
    private PasswordHistoryRepository repository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private PasswordHistoryService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PasswordHistoryService(repository, passwordEncoder);
    }

    @Test
    void shouldDetectReusedPassword() {
        PasswordHistory entry = PasswordHistory.builder()
                .userId(userId)
                .passwordHash("$2a$10$hashed1")
                .build();
        when(repository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(entry));
        when(passwordEncoder.matches("oldPassword", "$2a$10$hashed1")).thenReturn(true);

        assertThat(service.isPasswordReused(userId, "oldPassword")).isTrue();
    }

    @Test
    void shouldAllowNewPassword() {
        PasswordHistory entry = PasswordHistory.builder()
                .userId(userId)
                .passwordHash("$2a$10$hashed1")
                .build();
        when(repository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(entry));
        when(passwordEncoder.matches("brandNewPassword", "$2a$10$hashed1")).thenReturn(false);

        assertThat(service.isPasswordReused(userId, "brandNewPassword")).isFalse();
    }

    @Test
    void shouldReturnFalseWhenNoHistory() {
        when(repository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

        assertThat(service.isPasswordReused(userId, "anyPassword")).isFalse();
    }

    @Test
    void shouldRecordPasswordAndSave() {
        when(repository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

        service.recordPassword(userId, "$2a$10$encoded");

        ArgumentCaptor<PasswordHistory> captor = ArgumentCaptor.forClass(PasswordHistory.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("$2a$10$encoded");
    }

    @Test
    void shouldPruneOldEntriesBeyondLimit() {
        // Simulate 6 entries after recording (exceeds MAX_HISTORY=5)
        List<PasswordHistory> sixEntries = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            sixEntries.add(PasswordHistory.builder()
                    .userId(userId)
                    .passwordHash("$2a$10$hash" + i)
                    .build());
        }
        when(repository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(sixEntries);

        service.recordPassword(userId, "$2a$10$newHash");

        verify(repository).save(any(PasswordHistory.class));
        verify(repository).deleteAll(argThat(list -> ((List<?>) list).size() == 1));
    }

    @Test
    void shouldOnlyCheckLastFiveEntries() {
        // 7 entries but only first 5 should be checked
        List<PasswordHistory> entries = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            entries.add(PasswordHistory.builder()
                    .userId(userId)
                    .passwordHash("$2a$10$hash" + i)
                    .build());
        }
        when(repository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(entries);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        service.isPasswordReused(userId, "test");

        // Should only call matches for 5 entries (limit applied)
        verify(passwordEncoder, atMost(5)).matches(anyString(), anyString());
    }
}
