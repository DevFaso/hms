package com.example.hms.service.impl;

import com.example.hms.enums.ConsultationStatus;
import com.example.hms.enums.ConsultationUrgency;
import com.example.hms.model.Consultation;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.consultation.ConsultationResponseDTO;
import com.example.hms.repository.ConsultationRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.NotificationService;
import com.example.hms.utility.RoleValidator;
import jakarta.persistence.EntityNotFoundException;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.jpa.JpaObjectRetrievalFailureException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Resilience tests for {@link ConsultationServiceImpl#getAllConsultations} (which
 * invokes the private {@code toResponseDTO}).
 *
 * <p>Reproduces the production failure where {@code GET /api/consultations}
 * returned HTTP 500 because a Consultation row's {@code patient_id} (or other
 * FK) referenced a row that was hard-deleted. Hibernate raised
 * {@link EntityNotFoundException} when the lazy proxy initialised, the
 * global exception handler converted that to 500, and the entire list
 * response was lost over a single bad row.
 *
 * <p>The fix wraps every lazy association read in {@code safeInit}, which
 * degrades to {@code null} on dangling FK. These tests stub
 * {@link Hibernate#initialize} to throw and assert the service still
 * returns a usable list with the bad row's parent fields nulled out.
 */
@ExtendWith(MockitoExtension.class)
class ConsultationServiceResilienceTest {

    @Mock private ConsultationRepository consultationRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private RoleValidator roleValidator;
    @Mock private NotificationService notificationService;

    @InjectMocks private ConsultationServiceImpl service;

    private UUID consultationId;
    private Patient patient;
    private Hospital hospital;
    private Staff requestingProvider;
    private Staff consultant;
    private Encounter encounter;

    @BeforeEach
    void setUp() {
        consultationId = UUID.randomUUID();

        patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Jane");
        patient.setLastName("Roe");

        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("Test Hospital");

        requestingProvider = new Staff();
        requestingProvider.setId(UUID.randomUUID());

        consultant = new Staff();
        consultant.setId(UUID.randomUUID());

        encounter = new Encounter();
        encounter.setId(UUID.randomUUID());
    }

    private Consultation buildConsultation() {
        Consultation c = Consultation.builder()
            .patient(patient)
            .hospital(hospital)
            .requestingProvider(requestingProvider)
            .consultant(consultant)
            .encounter(encounter)
            .status(ConsultationStatus.REQUESTED)
            .urgency(ConsultationUrgency.ROUTINE)
            .build();
        c.setId(consultationId);
        return c;
    }

    @Test
    void getAllConsultations_danglingPatientFk_returnsListWithNulledPatientFields() {
        // SUPER_ADMIN cross-tenant call (no active hospital scope).
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(consultationRepository.findAllByOrderByRequestedAtDesc())
            .thenReturn(List.of(buildConsultation()));

        try (MockedStatic<Hibernate> hibernate = mockStatic(Hibernate.class)) {
            // Simulate dangling Patient FK only.
            hibernate.when(() -> Hibernate.initialize(any())).then(invocation -> {
                Object arg = invocation.getArgument(0);
                if (arg instanceof Patient) {
                    throw new EntityNotFoundException("Unable to find Patient");
                }
                return null;
            });

            List<ConsultationResponseDTO> result = service.getAllConsultations(null);

            assertThat(result).hasSize(1);
            ConsultationResponseDTO dto = result.get(0);
            assertThat(dto.getId()).isEqualTo(consultationId);
            // Patient fields degrade gracefully — endpoint stays 200.
            assertThat(dto.getPatientId()).isNull();
            assertThat(dto.getPatientName()).isNull();
            assertThat(dto.getPatientMrn()).isNull();
            // Other fields remain populated.
            assertThat(dto.getHospitalId()).isEqualTo(hospital.getId());
            assertThat(dto.getRequestingProviderId()).isEqualTo(requestingProvider.getId());
            assertThat(dto.getConsultantId()).isEqualTo(consultant.getId());
            assertThat(dto.getEncounterId()).isEqualTo(encounter.getId());
        }
    }

    @Test
    void getAllConsultations_danglingHospitalFk_returnsListWithNulledHospitalFields() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(consultationRepository.findAllByOrderByRequestedAtDesc())
            .thenReturn(List.of(buildConsultation()));

        try (MockedStatic<Hibernate> hibernate = mockStatic(Hibernate.class)) {
            hibernate.when(() -> Hibernate.initialize(any())).then(invocation -> {
                Object arg = invocation.getArgument(0);
                if (arg instanceof Hospital) {
                    throw new EntityNotFoundException("Unable to find Hospital");
                }
                return null;
            });

            List<ConsultationResponseDTO> result = service.getAllConsultations(null);

            assertThat(result).hasSize(1);
            ConsultationResponseDTO dto = result.get(0);
            assertThat(dto.getHospitalId()).isNull();
            assertThat(dto.getHospitalName()).isNull();
            // Patient still resolves but MRN cannot be computed without hospitalId.
            assertThat(dto.getPatientId()).isEqualTo(patient.getId());
            assertThat(dto.getPatientMrn()).isNull();
        }
    }

    @Test
    void getAllConsultations_jpaObjectRetrievalFailureException_alsoCaught() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(consultationRepository.findAllByOrderByRequestedAtDesc())
            .thenReturn(List.of(buildConsultation()));

        try (MockedStatic<Hibernate> hibernate = mockStatic(Hibernate.class)) {
            hibernate.when(() -> Hibernate.initialize(any())).then(invocation -> {
                Object arg = invocation.getArgument(0);
                if (arg instanceof Encounter) {
                    throw new JpaObjectRetrievalFailureException(
                        new EntityNotFoundException("encounter row deleted"));
                }
                return null;
            });

            assertThatNoException().isThrownBy(() -> {
                List<ConsultationResponseDTO> result = service.getAllConsultations(null);
                assertThat(result).hasSize(1);
                assertThat(result.get(0).getEncounterId()).isNull();
            });
        }
    }

    @Test
    void getAllConsultations_noDanglingFks_allFieldsPopulated() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(consultationRepository.findAllByOrderByRequestedAtDesc())
            .thenReturn(List.of(buildConsultation()));

        // No mockStatic — Hibernate.initialize runs normally on real entities (no-op).
        List<ConsultationResponseDTO> result = service.getAllConsultations(null);

        assertThat(result).hasSize(1);
        ConsultationResponseDTO dto = result.get(0);
        assertThat(dto.getId()).isEqualTo(consultationId);
        assertThat(dto.getPatientId()).isEqualTo(patient.getId());
        assertThat(dto.getHospitalId()).isEqualTo(hospital.getId());
        assertThat(dto.getRequestingProviderId()).isEqualTo(requestingProvider.getId());
        assertThat(dto.getConsultantId()).isEqualTo(consultant.getId());
        assertThat(dto.getEncounterId()).isEqualTo(encounter.getId());
    }
}
