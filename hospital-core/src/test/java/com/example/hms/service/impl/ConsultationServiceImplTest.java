package com.example.hms.service.impl;

import com.example.hms.enums.ConsultationStatus;
import com.example.hms.enums.ConsultationUrgency;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Consultation;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.consultation.ConsultationRequestDTO;
import com.example.hms.payload.dto.consultation.ConsultationResponseDTO;
import com.example.hms.payload.dto.consultation.ConsultationUpdateDTO;
import com.example.hms.payload.dto.consultation.CompleteConsultationRequestDTO;
import com.example.hms.service.NotificationService;
import com.example.hms.repository.ConsultationRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConsultationServiceImplTest {

    @Mock private ConsultationRepository consultationRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private PatientHospitalRegistrationRepository patientHospitalRegistrationRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private com.example.hms.utility.RoleValidator roleValidator;
    @Mock private NotificationService notificationService;

    @InjectMocks private ConsultationServiceImpl service;

    private UUID patientId, hospitalId, staffId, consultationId;
    private Patient patient;
    private Hospital hospital;
    private Staff staff;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        staffId = UUID.randomUUID();
        consultationId = UUID.randomUUID();
        patient = new Patient(); patient.setId(patientId); patient.setFirstName("John"); patient.setLastName("Doe");
        hospital = new Hospital(); hospital.setId(hospitalId); hospital.setName("General Hospital");
        staff = new Staff(); staff.setId(staffId);
        // Sonar S6809: production wires `self` via setSelf(@Lazy ...).
        // Point self at the SUT here so the in-class delegate call
        // doesn't NPE in the unit test.
        service.setSelf(service);
    }

    @AfterEach
    void tearDown() {
        // Tests that populate HospitalContextHolder must clear it so
        // the thread-local doesn't leak into a sibling test that
        // expects an empty context.
        HospitalContextHolder.clear();
    }

    private Consultation buildConsultation(ConsultationStatus status) {
        Consultation c = Consultation.builder().patient(patient).hospital(hospital).status(status).build();
        c.setId(consultationId);
        return c;
    }

    @Test void createConsultation_success() {
        ConsultationRequestDTO r = new ConsultationRequestDTO();
        r.setPatientId(patientId); r.setHospitalId(hospitalId);
        r.setSpecialtyRequested("Cardiology"); r.setUrgency(ConsultationUrgency.ROUTINE);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(patientHospitalRegistrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(true);
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(consultationRepository.save(any())).thenAnswer(i -> { Consultation c = i.getArgument(0); c.setId(consultationId); return c; });
        ConsultationResponseDTO result = service.createConsultation(r, staffId);
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(ConsultationStatus.REQUESTED);
    }

    @Test void createConsultation_patientNotFound() {
        ConsultationRequestDTO r = new ConsultationRequestDTO();
        r.setPatientId(patientId); r.setHospitalId(hospitalId); r.setUrgency(ConsultationUrgency.URGENT);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createConsultation(r, staffId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void getConsultation_success() {
        when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(buildConsultation(ConsultationStatus.REQUESTED)));
        ConsultationResponseDTO result = service.getConsultation(consultationId);
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(consultationId);
    }

    @Test void getConsultation_notFound() {
        when(consultationRepository.findById(consultationId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getConsultation(consultationId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void getConsultationsForPatient() {
        Consultation c1 = buildConsultation(ConsultationStatus.REQUESTED); c1.setId(UUID.randomUUID());
        Consultation c2 = buildConsultation(ConsultationStatus.COMPLETED); c2.setId(UUID.randomUUID());
        when(consultationRepository.findByPatient_IdOrderByRequestedAtDesc(patientId)).thenReturn(List.of(c1, c2));
        assertThat(service.getConsultationsForPatient(patientId)).hasSize(2);
    }

    @Test void getConsultationsForHospital_withStatus() {
        when(consultationRepository.findByHospital_IdAndStatusOrderByRequestedAtDesc(hospitalId, ConsultationStatus.REQUESTED)).thenReturn(List.of(buildConsultation(ConsultationStatus.REQUESTED)));
        assertThat(service.getConsultationsForHospital(hospitalId, ConsultationStatus.REQUESTED)).hasSize(1);
    }

    @Test void getConsultationsForHospital_withoutStatus() {
        // After fix: no-status path returns ALL consultations for the hospital
        // (matches dashboard count(*) tile semantics). Was previously filtered
        // to [REQUESTED, ACKNOWLEDGED, SCHEDULED, IN_PROGRESS] which silently
        // hid COMPLETED / CANCELLED rows and produced "tile says 3, list shows 0".
        when(consultationRepository.findByHospital_IdOrderByRequestedAtDesc(hospitalId)).thenReturn(List.of());
        assertThat(service.getConsultationsForHospital(hospitalId, null)).isEmpty();
    }

    @Test void acknowledgeConsultation_success() {
        when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(buildConsultation(ConsultationStatus.REQUESTED)));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(consultationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(service.acknowledgeConsultation(consultationId, staffId).getStatus()).isEqualTo(ConsultationStatus.ACKNOWLEDGED);
    }

    @Test void acknowledgeConsultation_alreadyAcknowledged() {
        when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(buildConsultation(ConsultationStatus.ACKNOWLEDGED)));
        assertThatThrownBy(() -> service.acknowledgeConsultation(consultationId, staffId)).isInstanceOf(BusinessException.class);
    }

    @Test void completeConsultation_success() {
        CompleteConsultationRequestDTO u = CompleteConsultationRequestDTO.builder().recommendations("All good").consultantNote("All good").build();
        when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(buildConsultation(ConsultationStatus.IN_PROGRESS)));
        when(consultationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        ConsultationResponseDTO r = service.completeConsultation(consultationId, u);
        assertThat(r.getStatus()).isEqualTo(ConsultationStatus.COMPLETED);
        assertThat(r.getConsultantNote()).isEqualTo("All good");
    }

    @Test void completeConsultation_alreadyCompleted() {
        when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(buildConsultation(ConsultationStatus.COMPLETED)));
        CompleteConsultationRequestDTO dto = CompleteConsultationRequestDTO.builder().recommendations("x").build();
        assertThatThrownBy(() -> service.completeConsultation(consultationId, dto)).isInstanceOf(BusinessException.class);
    }

    @Test void completeConsultation_cancelled() {
        when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(buildConsultation(ConsultationStatus.CANCELLED)));
        CompleteConsultationRequestDTO dto = CompleteConsultationRequestDTO.builder().recommendations("x").build();
        assertThatThrownBy(() -> service.completeConsultation(consultationId, dto)).isInstanceOf(BusinessException.class);
    }

    @Test void cancelConsultation_success() {
        when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(buildConsultation(ConsultationStatus.REQUESTED)));
        when(consultationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        ConsultationResponseDTO r = service.cancelConsultation(consultationId, "No longer needed");
        assertThat(r.getStatus()).isEqualTo(ConsultationStatus.CANCELLED);
        assertThat(r.getCancellationReason()).isEqualTo("No longer needed");
    }

    @Test void cancelConsultation_alreadyCompleted() {
        when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(buildConsultation(ConsultationStatus.COMPLETED)));
        assertThatThrownBy(() -> service.cancelConsultation(consultationId, "r")).isInstanceOf(BusinessException.class);
    }

    @Test void cancelConsultation_alreadyCancelled() {
        when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(buildConsultation(ConsultationStatus.CANCELLED)));
        assertThatThrownBy(() -> service.cancelConsultation(consultationId, "r")).isInstanceOf(BusinessException.class);
    }

    @Test void updateConsultation_setsScheduledStatus() {
        ConsultationUpdateDTO u = new ConsultationUpdateDTO(); u.setScheduledAt(LocalDateTime.now().plusDays(1));
        when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(buildConsultation(ConsultationStatus.ACKNOWLEDGED)));
        when(consultationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(service.updateConsultation(consultationId, u).getStatus()).isEqualTo(ConsultationStatus.SCHEDULED);
    }

    @Test void getConsultationsRequestedBy() {
        when(consultationRepository.findByRequestingProvider_IdOrderByRequestedAtDesc(staffId)).thenReturn(List.of());
        assertThat(service.getConsultationsRequestedBy(staffId)).isEmpty();
    }

    @Test void getConsultationsAssignedTo_withStatus() {
        when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(staffId, ConsultationStatus.REQUESTED)).thenReturn(List.of());
        assertThat(service.getConsultationsAssignedTo(staffId, ConsultationStatus.REQUESTED)).isEmpty();
    }

    @Test void getConsultationsAssignedTo_withoutStatus() {
        when(consultationRepository.findByConsultant_IdOrderByRequestedAtDesc(staffId)).thenReturn(List.of());
        assertThat(service.getConsultationsAssignedTo(staffId, null)).isEmpty();
    }

    @Test void getPendingConsultations() {
        when(consultationRepository.findByHospitalAndStatuses(eq(hospitalId), any())).thenReturn(List.of());
        assertThat(service.getPendingConsultations(hospitalId)).isEmpty();
    }

    // ── Tenant isolation tests ──

    @Test void getConsultation_crossHospital_throws404() {
        UUID otherHospitalId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(otherHospitalId);
        when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(buildConsultation(ConsultationStatus.REQUESTED)));
        assertThatThrownBy(() -> service.getConsultation(consultationId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void getConsultation_sameHospital_success() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(buildConsultation(ConsultationStatus.REQUESTED)));
        ConsultationResponseDTO result = service.getConsultation(consultationId);
        assertThat(result).isNotNull();
    }

    @Test void getConsultation_superAdmin_noFilter() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(buildConsultation(ConsultationStatus.REQUESTED)));
        ConsultationResponseDTO result = service.getConsultation(consultationId);
        assertThat(result).isNotNull();
    }

    @Test void getConsultationsForPatient_scopedToHospital() {
        UUID otherHospId = UUID.randomUUID();
        Hospital otherHosp = new Hospital(); otherHosp.setId(otherHospId);
        Consultation ownHosp = buildConsultation(ConsultationStatus.REQUESTED); ownHosp.setId(UUID.randomUUID());
        Consultation otherHospConsult = Consultation.builder().patient(patient).hospital(otherHosp).status(ConsultationStatus.REQUESTED).build();
        otherHospConsult.setId(UUID.randomUUID());

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(consultationRepository.findByPatient_IdOrderByRequestedAtDesc(patientId)).thenReturn(List.of(ownHosp, otherHospConsult));

        List<ConsultationResponseDTO> result = service.getConsultationsForPatient(patientId);
        assertThat(result).hasSize(1);
    }

    @Test void getConsultationsForPatient_superAdmin_noFilter() {
        Consultation c1 = buildConsultation(ConsultationStatus.REQUESTED); c1.setId(UUID.randomUUID());
        Consultation c2 = buildConsultation(ConsultationStatus.COMPLETED); c2.setId(UUID.randomUUID());

        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(consultationRepository.findByPatient_IdOrderByRequestedAtDesc(patientId)).thenReturn(List.of(c1, c2));

        assertThat(service.getConsultationsForPatient(patientId)).hasSize(2);
    }

    @Test void getAllConsultations_scopedToHospital_withStatus() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(consultationRepository.findByHospital_IdAndStatusOrderByRequestedAtDesc(hospitalId, ConsultationStatus.REQUESTED))
            .thenReturn(List.of(buildConsultation(ConsultationStatus.REQUESTED)));

        assertThat(service.getAllConsultations(ConsultationStatus.REQUESTED)).hasSize(1);
    }

    @Test void getAllConsultations_superAdmin_withStatus() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(consultationRepository.findByStatusOrderByRequestedAtDesc(ConsultationStatus.REQUESTED)).thenReturn(List.of());

        assertThat(service.getAllConsultations(ConsultationStatus.REQUESTED)).isEmpty();
    }

    @Test void getAllConsultations_superAdmin_noStatus() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(consultationRepository.findAllByOrderByRequestedAtDesc()).thenReturn(List.of());

        assertThat(service.getAllConsultations(null)).isEmpty();
    }

    /**
     * Super-admin global view (JWT claim set, no X-Hospital-Id header)
     * takes the new short-circuit branch — bypasses
     * {@link com.example.hms.utility.RoleValidator#requireActiveHospitalId()}
     * entirely and goes straight to the unscoped repository read.
     *
     * <p>Pinned because the dashboard-tile-vs-list mismatch (count 3 /
     * list "No consultations across any of your hospitals") that
     * surfaced on the develop deployment was traced to a path where
     * {@code requireActiveHospitalId()} would resolve a stale
     * primary-hospital fallback for a super-admin; the carve-out
     * fixes that by trusting the JWT claim directly.
     */
    @Test void getAllConsultations_superAdminGlobalView_skipsRequireActiveHospitalId() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .superAdmin(true)
            .headerOverridden(false)
            .build());
        when(consultationRepository.findAllByOrderByRequestedAtDesc())
            .thenReturn(List.of(buildConsultation(ConsultationStatus.REQUESTED)));

        assertThat(service.getAllConsultations(null)).hasSize(1);

        // Carve-out fires before requireActiveHospitalId(), so the
        // RoleValidator gate is never reached.
        verify(roleValidator, never()).requireActiveHospitalId();
        verify(consultationRepository, never()).findByHospital_IdOrderByRequestedAtDesc(any());
    }

    /**
     * Super-admin chip-scoped (X-Hospital-Id header set so
     * headerOverridden=true) MUST fall through to
     * {@code requireActiveHospitalId()} so the list reflects the chip,
     * not the system-wide unscoped fetch. Without this, the chip
     * becomes a visual no-op for super-admins.
     */
    @Test void getAllConsultations_superAdminChipScoped_doesNotShortCircuit() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .superAdmin(true)
            .headerOverridden(true)
            .activeHospitalId(hospitalId)
            .build());
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(consultationRepository.findByHospital_IdOrderByRequestedAtDesc(hospitalId))
            .thenReturn(List.of(buildConsultation(ConsultationStatus.REQUESTED)));

        assertThat(service.getAllConsultations(null)).hasSize(1);

        verify(roleValidator).requireActiveHospitalId();
        verify(consultationRepository, never()).findAllByOrderByRequestedAtDesc();
    }

    @Test void getConsultationsRequestedBy_scopedToHospital() {
        UUID otherHospId = UUID.randomUUID();
        Hospital otherHosp = new Hospital(); otherHosp.setId(otherHospId);
        Consultation ownHosp = buildConsultation(ConsultationStatus.REQUESTED); ownHosp.setId(UUID.randomUUID());
        Consultation otherHospConsult = Consultation.builder().patient(patient).hospital(otherHosp).status(ConsultationStatus.REQUESTED).build();
        otherHospConsult.setId(UUID.randomUUID());

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(consultationRepository.findByRequestingProvider_IdOrderByRequestedAtDesc(staffId)).thenReturn(List.of(ownHosp, otherHospConsult));

        assertThat(service.getConsultationsRequestedBy(staffId)).hasSize(1);
    }

    @Test void getConsultationsRequestedBy_superAdmin_noFilter() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(consultationRepository.findByRequestingProvider_IdOrderByRequestedAtDesc(staffId)).thenReturn(List.of());
        assertThat(service.getConsultationsRequestedBy(staffId)).isEmpty();
    }
}
