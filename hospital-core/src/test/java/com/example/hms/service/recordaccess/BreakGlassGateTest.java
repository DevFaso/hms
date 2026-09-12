package com.example.hms.service.recordaccess;

import com.example.hms.model.BreakGlassSession;
import com.example.hms.model.Hospital;
import com.example.hms.repository.BreakGlassSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BreakGlassGateTest {

    @Mock
    private BreakGlassSessionRepository sessionRepository;

    @InjectMocks
    private BreakGlassGate gate;

    private final UUID actor = UUID.randomUUID();
    private final UUID patient = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();

    private BreakGlassSession sessionAt(UUID hospital) {
        Hospital h = new Hospital();
        h.setId(hospital);
        BreakGlassSession s = new BreakGlassSession();
        s.setId(UUID.randomUUID());
        s.setHospital(h);
        return s;
    }

    @Test
    @DisplayName("a live session declared at the acting hospital unlocks")
    void liveSessionHereUnlocks() {
        BreakGlassSession s = sessionAt(hospitalId);
        when(sessionRepository.findLiveForUserAndPatient(eq(actor), eq(patient), any())).thenReturn(List.of(s));

        assertThat(gate.isUnlocked(actor, patient, hospitalId)).isTrue();
        assertThat(gate.liveSessionId(actor, patient, hospitalId)).contains(s.getId());
    }

    @Test
    @DisplayName("a session declared at another hospital does not count here")
    void sessionElsewhereDoesNotUnlock() {
        when(sessionRepository.findLiveForUserAndPatient(eq(actor), eq(patient), any()))
            .thenReturn(List.of(sessionAt(UUID.randomUUID())));

        assertThat(gate.isUnlocked(actor, patient, hospitalId)).isFalse();
        assertThat(gate.liveSessionId(actor, patient, hospitalId)).isEmpty();
    }

    @Test
    @DisplayName("no session, no unlock")
    void noSession() {
        when(sessionRepository.findLiveForUserAndPatient(eq(actor), eq(patient), any())).thenReturn(List.of());
        assertThat(gate.isUnlocked(actor, patient, hospitalId)).isFalse();
    }

    @Test
    @DisplayName("a missing actor, patient or hospital never reaches the repository")
    void nullsShortCircuit() {
        assertThat(gate.isUnlocked(null, patient, hospitalId)).isFalse();
        assertThat(gate.isUnlocked(actor, null, hospitalId)).isFalse();
        assertThat(gate.isUnlocked(actor, patient, null)).isFalse();
        verify(sessionRepository, never()).findLiveForUserAndPatient(any(), any(), any());
    }
}
