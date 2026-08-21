package com.example.hms.service.impl;

import com.example.hms.enums.AdvanceDirectiveStatus;
import com.example.hms.enums.AdvanceDirectiveType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.AdvanceDirectiveMapper;
import com.example.hms.model.AdvanceDirective;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.AdvanceDirectiveRequestDTO;
import com.example.hms.repository.AdvanceDirectiveRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Advance-directive writes (P2 #13).
 *
 * <p>The entity, repository, mapper and response DTO all existed and the record
 * was surfaced by the storyboard and by record-sharing — but nothing could
 * write one. A DNR that cannot be entered is a DNR that does not exist.
 */
@ExtendWith(MockitoExtension.class)
class AdvanceDirectiveServiceImplTest {

    @Mock private AdvanceDirectiveRepository directiveRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private RoleValidator roleValidator;

    private AdvanceDirectiveServiceImpl service;

    private UUID patientId;
    private UUID hospitalId;
    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        service = new AdvanceDirectiveServiceImpl(
            directiveRepository, patientRepository, hospitalRepository,
            new AdvanceDirectiveMapper(), roleValidator);

        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();

        patient = new Patient();
        patient.setId(patientId);

        hospital = Hospital.builder().name("CHU").code("CHU").build();
        hospital.setId(hospitalId);
    }

    private AdvanceDirectiveRequestDTO request() {
        return AdvanceDirectiveRequestDTO.builder()
            .directiveType(AdvanceDirectiveType.DO_NOT_RESUSCITATE)
            .description("Do not resuscitate")
            .build();
    }

    @Test
    void createDefaultsToActive() {
        // A newly recorded directive is in force. Requiring the caller to say so
        // would make "forgot to set status" mean "not in force", which is the
        // wrong way round for a DNR.
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(directiveRepository.save(any(AdvanceDirective.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(service.create(patientId, request()).getStatus())
            .isEqualTo(AdvanceDirectiveStatus.ACTIVE.name());
    }

    @Test
    void createRefusesADirectiveThatExpiresBeforeItTakesEffect() {
        // Storing one guarantees a later reader draws the wrong conclusion about
        // which directive was in force.
        AdvanceDirectiveRequestDTO req = request();
        req.setEffectiveDate(LocalDate.of(2026, 6, 1));
        req.setExpirationDate(LocalDate.of(2026, 5, 1));

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));

        assertThatThrownBy(() -> service.create(patientId, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("expire before it takes effect");
        verify(directiveRepository, never()).save(any());
    }

    @Test
    void createPinsAScopedCallerToTheirOwnHospital() {
        // The client-supplied hospitalId would otherwise be a cross-tenant write
        // vector.
        AdvanceDirectiveRequestDTO req = request();
        req.setHospitalId(UUID.randomUUID());

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(directiveRepository.save(any(AdvanceDirective.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(service.create(patientId, req).getHospitalId()).isEqualTo(hospitalId);
    }

    @Test
    void revokeMarksItRevokedRatherThanDeletingTheRow() {
        // A directive that was in force and later withdrawn is part of the
        // clinical record; deleting it destroys the evidence that it applied.
        UUID id = UUID.randomUUID();
        AdvanceDirective directive = new AdvanceDirective();
        directive.setId(id);
        directive.setHospital(hospital);
        directive.setPatient(patient);
        directive.setStatus(AdvanceDirectiveStatus.ACTIVE);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(directiveRepository.findById(id)).thenReturn(Optional.of(directive));
        when(directiveRepository.save(any(AdvanceDirective.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(service.revoke(id).getStatus()).isEqualTo(AdvanceDirectiveStatus.REVOKED.name());
        verify(directiveRepository, never()).delete(any());
    }

    @Test
    void anotherHospitalsDirectiveReadsAsAbsent() {
        UUID id = UUID.randomUUID();
        AdvanceDirective foreign = new AdvanceDirective();
        foreign.setId(id);
        Hospital other = Hospital.builder().name("Other").code("OTH").build();
        other.setId(UUID.randomUUID());
        foreign.setHospital(other);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(directiveRepository.findById(id)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.revoke(id))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
