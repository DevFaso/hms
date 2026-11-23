package com.example.hms.service.notification;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.model.Patient;
import com.example.hms.repository.PatientRepository;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PatientRecipientContextTest {
    @Test
    void aFreshPatientReadIsRequiredAndMissingPatientsAreRefused() {
        PatientRepository patients = mock(PatientRepository.class);
        PatientRecipientVerification verification = mock(PatientRecipientVerification.class);
        var check = new PatientRecipientCurrentCheck(patients, verification);
        UUID id = UUID.randomUUID(); Patient patient = new Patient();
        when(patients.findByIdUnscoped(id)).thenReturn(Optional.empty(), Optional.of(patient));
        assertThat(check.allows(id, NotificationChannel.EMAIL, "cdx@example.invalid")).isFalse();
        when(verification.allows(patient, NotificationChannel.EMAIL, "cdx@example.invalid")).thenReturn(true);
        assertThat(check.allows(id, NotificationChannel.EMAIL, "cdx@example.invalid")).isTrue();
        verify(patients, times(2)).findByIdUnscoped(id);
    }

    @Test
    void nestedMailScopesRestoreTheParentAndClearEvenAfterAnException() {
        UUID outer = UUID.randomUUID(), inner = UUID.randomUUID();
        assertThat(VerifiedPatientMailScope.current()).isNull();
        assertThatThrownBy(() -> VerifiedPatientMailScope.run(outer, "outer@example.invalid", () -> {
            assertThat(VerifiedPatientMailScope.current().patientId()).isEqualTo(outer);
            VerifiedPatientMailScope.run(inner, "inner@example.invalid", () ->
                assertThat(VerifiedPatientMailScope.current().patientId()).isEqualTo(inner));
            assertThat(VerifiedPatientMailScope.current().patientId()).isEqualTo(outer);
            assertThat(VerifiedPatientMailScope.current().toString()).doesNotContain("outer@example.invalid");
            throw new IllegalStateException("cdx-fixture");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(VerifiedPatientMailScope.current()).isNull();
    }
}
