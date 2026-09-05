package com.example.hms.service;

import com.example.hms.repository.AppointmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReminderClaimServiceTest {

    @Test
    void winsOnlyWhenTheConditionalUpdateTouchedARow() {
        AppointmentRepository repository = mock(AppointmentRepository.class);
        UUID id = UUID.randomUUID();
        when(repository.claimReminder(eq(id), any())).thenReturn(1, 0);
        ReminderClaimService service = new ReminderClaimService(repository);

        assertThat(service.claim(id, LocalDateTime.of(2026, 9, 5, 8, 0))).isTrue();
        assertThat(service.claim(id, LocalDateTime.of(2026, 9, 5, 8, 0))).isFalse();
    }

    @Test
    void claimCommitsOnItsOwn() throws NoSuchMethodException {
        // The whole point: the stamp must survive the sweep's transaction
        // rolling back after the send. REQUIRES_NEW on a separate bean.
        Transactional tx = ReminderClaimService.class
            .getMethod("claim", UUID.class, LocalDateTime.class)
            .getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
