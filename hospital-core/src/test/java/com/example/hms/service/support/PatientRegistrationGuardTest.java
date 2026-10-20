package com.example.hms.service.support;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PatientRegistrationGuardTest {

    private final PatientHospitalRegistrationRepository repository = mock(PatientHospitalRegistrationRepository.class);
    private final PatientRegistrationGuard guard = new PatientRegistrationGuard(repository);
    private final UUID patientId = UUID.randomUUID();
    private final UUID hospitalA = UUID.randomUUID();
    private final UUID hospitalB = UUID.randomUUID();

    @Test
    void registeredPatientPasses() {
        when(repository.existsByPatientIdAndHospitalId(patientId, hospitalA)).thenReturn(true);
        assertThatCode(() -> guard.requireRegisteredAt(patientId, hospitalA)).doesNotThrowAnyException();
    }

    @Test
    void patientRegisteredOnlyElsewhereAnswersAsUnknown() {
        when(repository.existsByPatientIdAndHospitalId(patientId, hospitalA)).thenReturn(true);
        assertThatThrownBy(() -> guard.requireRegisteredAt(patientId, hospitalB))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void missingHospitalOrPatientIsRefused() {
        assertThatThrownBy(() -> guard.requireRegisteredAt(patientId, null))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> guard.requireRegisteredAt(null, hospitalA))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
