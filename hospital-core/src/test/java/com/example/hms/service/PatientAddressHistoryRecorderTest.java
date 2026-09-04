package com.example.hms.service;

import com.example.hms.model.Patient;
import com.example.hms.model.PatientAddressHistory;
import com.example.hms.repository.PatientAddressHistoryRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The recorder branches the service-level hook tests cannot reach: the
 * compose-from-parts fallback (the stored composed line can lag the parts)
 * and the best-effort audit swallow.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PatientAddressHistoryRecorderTest {

    @Mock private PatientAddressHistoryRepository addressHistoryRepository;
    @Mock private RoleValidator roleValidator;
    @Mock private AuditEventLogService auditService;

    @InjectMocks
    private PatientAddressHistoryRecorder recorder;

    private Patient patient;

    @BeforeEach
    void setUp() {
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        when(addressHistoryRepository.save(any())).thenAnswer(inv -> {
            PatientAddressHistory h = inv.getArgument(0);
            h.setId(UUID.randomUUID());
            return h;
        });
    }

    @Test
    @DisplayName("when the stored composed line lags the parts, the row composes it from the parts")
    void composesTheLineFromPartsWhenBlank() {
        // Before: parts set, composed line blank (the full-form path can set
        // parts from the DTO while omitting address).
        patient.setAddress(null);
        patient.setAddressLine1("Secteur 4, Rue 12");
        patient.setCity("Bobo-Dioulasso");
        patient.setCountry("Burkina Faso");
        var before = recorder.snapshot(patient);

        patient.setAddressLine1("Secteur 30");
        patient.setCity("Ouagadougou");
        recorder.recordIfMoved(patient, before);

        ArgumentCaptor<PatientAddressHistory> captor =
            ArgumentCaptor.forClass(PatientAddressHistory.class);
        verify(addressHistoryRepository).save(captor.capture());
        assertThat(captor.getValue().getAddress())
            .isEqualTo("Secteur 4, Rue 12, Bobo-Dioulasso, Burkina Faso");
    }

    @Test
    @DisplayName("an audit-sink failure never undoes the recorded move")
    void auditFailureIsSwallowed() {
        doThrow(new RuntimeException("audit sink down")).when(auditService).logEvent(any());
        patient.setAddressLine1("Ancien secteur");
        patient.setCity("Kaya");
        var before = recorder.snapshot(patient);
        patient.setCity("Dori");

        assertThatCode(() -> recorder.recordIfMoved(patient, before))
            .doesNotThrowAnyException();
        verify(addressHistoryRepository).save(any());
    }
}
