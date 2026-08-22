package com.example.hms.security;

import com.example.hms.model.platform.PlatformDowntimeState;
import com.example.hms.repository.PlatformDowntimeStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Downtime cache (P3 #23a): the V80 singleton + 30s-cache pattern. The
 * fail-open direction matters — a missing row or DB blip must never turn
 * read-only mode ON by accident.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DowntimeStateServiceTest {

    @Mock private PlatformDowntimeStateRepository repository;

    @InjectMocks private DowntimeStateService service;

    private PlatformDowntimeState row;

    @BeforeEach
    void setUp() {
        row = new PlatformDowntimeState();
        row.setId(PlatformDowntimeState.SINGLETON_ID);
        row.setReadOnly(false);
        row.setUpdatedAt(Instant.now());
        when(repository.findById(PlatformDowntimeState.SINGLETON_ID)).thenReturn(Optional.of(row));
        when(repository.save(any(PlatformDowntimeState.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void refreshReadsTheSingleton() {
        row.setReadOnly(true);
        row.setMessage("Planned maintenance");

        service.refresh();

        assertThat(service.snapshot().readOnly()).isTrue();
        assertThat(service.snapshot().message()).isEqualTo("Planned maintenance");
    }

    @Test
    void missingRowFailsOpenToNormalOperation() {
        when(repository.findById(PlatformDowntimeState.SINGLETON_ID)).thenReturn(Optional.empty());

        service.refresh();

        assertThat(service.snapshot().readOnly()).isFalse();
    }

    @Test
    void dbBlipKeepsThePreviousValue() {
        row.setReadOnly(true);
        service.refresh();
        when(repository.findById(PlatformDowntimeState.SINGLETON_ID))
            .thenThrow(new IllegalStateException("connection refused"));

        service.refresh();

        // During an actual outage this is exactly when the mode must hold.
        assertThat(service.snapshot().readOnly()).isTrue();
    }

    @Test
    void setReadOnlyStampsAndUpdatesTheCacheSynchronously() {
        DowntimeStateService.DowntimeSnapshot snapshot =
            service.setReadOnly(true, "Switch upgrade", "superadmin");

        assertThat(snapshot.readOnly()).isTrue();
        assertThat(service.snapshot().readOnly()).isTrue();
        assertThat(row.getActivatedAt()).isNotNull();
        assertThat(row.getActivatedByUsername()).isEqualTo("superadmin");
        assertThat(row.getMessage()).isEqualTo("Switch upgrade");
    }

    @Test
    void deactivatingClearsTheActivationFields() {
        service.setReadOnly(true, "msg", "superadmin");

        service.setReadOnly(false, null, "superadmin");

        assertThat(service.snapshot().readOnly()).isFalse();
        assertThat(row.getActivatedAt()).isNull();
        assertThat(row.getMessage()).isNull();
        assertThat(row.getActivatedByUsername()).isNull();
    }
}
