package com.example.hms.service.support;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.impl.PatientPortalServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * An account linked to more than one patient row (V113 left existing
 * duplicates in place) used to get a 500 on every read of its own record.
 */
@ExtendWith(MockitoExtension.class)
class LinkedPatientLookupTest {

    @Mock private PatientRepository patientRepository;
    @Mock private ControllerAuthUtils authUtils;
    @Mock private Authentication auth;

    @InjectMocks private PatientPortalServiceImpl portal;

    private final UUID userId = UUID.randomUUID();

    @Test
    void oneLinkedRowIsReturned() {
        Patient patient = new Patient();
        when(patientRepository.findByUserId(userId)).thenReturn(Optional.of(patient));

        assertThat(LinkedPatientLookup.linkedPatient(patientRepository, userId)).containsSame(patient);
    }

    @Test
    void duplicateLinkedRowsAnswerNoneInsteadOfThrowing() {
        when(patientRepository.findByUserId(userId))
            .thenThrow(new IncorrectResultSizeDataAccessException(1, 2));

        assertThat(LinkedPatientLookup.linkedPatient(patientRepository, userId)).isEmpty();
    }

    @Test
    void thePortalAnswersADuplicateLikeAnUnlinkedAccountNotA500() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        when(patientRepository.findByUserId(userId))
            .thenThrow(new IncorrectResultSizeDataAccessException(1, 2));

        assertThatThrownBy(() -> portal.resolvePatientId(auth))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
