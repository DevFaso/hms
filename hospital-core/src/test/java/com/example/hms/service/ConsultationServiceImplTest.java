package com.example.hms.service;

import com.example.hms.enums.ConsultationStatus;
import com.example.hms.enums.ConsultationType;
import com.example.hms.enums.ConsultationUrgency;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Consultation;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.consultation.ConsultationRequestDTO;
import com.example.hms.payload.dto.consultation.ConsultationResponseDTO;
import com.example.hms.payload.dto.consultation.ConsultationUpdateDTO;
import com.example.hms.repository.ConsultationRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.impl.ConsultationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConsultationServiceImplTest {

    @Mock private ConsultationRepository consultationRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private EncounterRepository encounterRepository;

    @InjectMocks
    private ConsultationServiceImpl service;

    private final UUID patientId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID staffId = UUID.randomUUID();
    private final UUID consultantId = UUID.randomUUID();
    private final UUID encounterId = UUID.randomUUID();
    private final UUID consultationId = UUID.randomUUID();

    private Patient patient;
    private Hospital hospital;
    private Staff staff;
    private Staff consultant;
    private Encounter encounter;

    @BeforeEach
    void setUp() {
        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("John");
        patient.setLastName("Doe");

        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("General Hospital");

        staff = new Staff();
        staff.setId(staffId);

        consultant = new Staff();
        consultant.setId(consultantId);

        encounter = new Encounter();
        encounter.setId(encounterId);
    }

    private ConsultationRequestDTO buildRequest() {
        return ConsultationRequestDTO.builder()
                .patientId(patientId)
                .hospitalId(hospitalId)
                .consultationType(ConsultationType.INPATIENT_CONSULT)
                .specialtyRequested("Cardiology")
                .reasonForConsult("Chest pain evaluation")
                .clinicalQuestion("Rule out ACS?")
                .urgency(ConsultationUrgency.URGENT)
                .build();
    }

    private Consultation buildConsultation(ConsultationStatus status) {
        Consultation c = Consultation.builder()
                .patient(patient)
                .hospital(hospital)
                .requestingProvider(staff)
                .consultant(consultant)
                .consultationType(ConsultationType.INPATIENT_CONSULT)
                .specialtyRequested("Cardiology")
                .reasonForConsult("Chest pain evaluation")
                .urgency(ConsultationUrgency.URGENT)
                .status(status)
                .requestedAt(LocalDateTime.now())
                .build();
        c.setId(consultationId);
        return c;
    }

    // ── createConsultation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("createConsultation")
    class CreateConsultation {

        @Test
        @DisplayName("creates with encounter and preferred consultant")
        void createWithEncounterAndConsultant() {
            ConsultationRequestDTO request = buildRequest();
            request.setEncounterId(encounterId);
            request.setPreferredConsultantId(consultantId);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
            when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
            when(staffRepository.findById(consultantId)).thenReturn(Optional.of(consultant));
            when(consultationRepository.save(any(Consultation.class))).thenAnswer(inv -> {
                Consultation c = inv.getArgument(0);
                c.setId(consultationId);
                return c;
            });

            ConsultationResponseDTO result = service.createConsultation(request, staffId);

            assertThat(result).isNotNull();
            assertThat(result.getPatientId()).isEqualTo(patientId);
            assertThat(result.getStatus()).isEqualTo(ConsultationStatus.REQUESTED);
            verify(consultationRepository).save(any(Consultation.class));
        }

        @Test
        @DisplayName("creates without encounter and without consultant")
        void createWithoutEncounterAndConsultant() {
            ConsultationRequestDTO request = buildRequest();

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
            when(consultationRepository.save(any(Consultation.class))).thenAnswer(inv -> {
                Consultation c = inv.getArgument(0);
                c.setId(consultationId);
                return c;
            });

            ConsultationResponseDTO result = service.createConsultation(request, staffId);

            assertThat(result).isNotNull();
            assertThat(result.getConsultantId()).isNull();
        }

        @Test
        @DisplayName("creates with isCurbside set to true")
        void createWithCurbside() {
            ConsultationRequestDTO request = buildRequest();
            request.setIsCurbside(true);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
            when(consultationRepository.save(any(Consultation.class))).thenAnswer(inv -> {
                Consultation c = inv.getArgument(0);
                c.setId(consultationId);
                return c;
            });

            ConsultationResponseDTO result = service.createConsultation(request, staffId);

            assertThat(result.getIsCurbside()).isTrue();
        }

        @Test
        @DisplayName("throws when patient not found")
        void throwsWhenPatientNotFound() {
            ConsultationRequestDTO request = buildRequest();
            when(patientRepository.findById(patientId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createConsultation(request, staffId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("throws when hospital not found")
        void throwsWhenHospitalNotFound() {
            ConsultationRequestDTO request = buildRequest();
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createConsultation(request, staffId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("SLA calculation for STAT urgency")
        void slaDueByForStat() {
            ConsultationRequestDTO request = buildRequest();
            request.setUrgency(ConsultationUrgency.STAT);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
            when(consultationRepository.save(any(Consultation.class))).thenAnswer(inv -> {
                Consultation c = inv.getArgument(0);
                c.setId(consultationId);
                return c;
            });

            ConsultationResponseDTO result = service.createConsultation(request, staffId);

            assertThat(result.getSlaDueBy()).isNotNull();
            assertThat(result.getSlaDueBy()).isBefore(LocalDateTime.now().plusHours(3));
        }

        @Test
        @DisplayName("SLA calculation for ROUTINE urgency")
        void slaDueByForRoutine() {
            ConsultationRequestDTO request = buildRequest();
            request.setUrgency(ConsultationUrgency.ROUTINE);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
            when(consultationRepository.save(any(Consultation.class))).thenAnswer(inv -> {
                Consultation c = inv.getArgument(0);
                c.setId(consultationId);
                return c;
            });

            ConsultationResponseDTO result = service.createConsultation(request, staffId);

            assertThat(result.getSlaDueBy()).isAfter(LocalDateTime.now().plusDays(6));
        }

        @Test
        @DisplayName("resolves provider via userId fallback")
        void resolvesProviderViaUserId() {
            ConsultationRequestDTO request = buildRequest();
            UUID userId = UUID.randomUUID();

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(staffRepository.findById(userId)).thenReturn(Optional.empty());
            when(staffRepository.findByUserIdAndHospitalId(userId, hospitalId)).thenReturn(Optional.of(staff));
            when(consultationRepository.save(any(Consultation.class))).thenAnswer(inv -> {
                Consultation c = inv.getArgument(0);
                c.setId(consultationId);
                return c;
            });

            ConsultationResponseDTO result = service.createConsultation(request, userId);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("resolves provider via firstByUserId fallback")
        void resolvesProviderViaFirstByUserId() {
            ConsultationRequestDTO request = buildRequest();
            UUID userId = UUID.randomUUID();

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(staffRepository.findById(userId)).thenReturn(Optional.empty());
            when(staffRepository.findByUserIdAndHospitalId(userId, hospitalId)).thenReturn(Optional.empty());
            when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(userId)).thenReturn(Optional.of(staff));
            when(consultationRepository.save(any(Consultation.class))).thenAnswer(inv -> {
                Consultation c = inv.getArgument(0);
                c.setId(consultationId);
                return c;
            });

            ConsultationResponseDTO result = service.createConsultation(request, userId);

            assertThat(result).isNotNull();
        }
    }

    // ── getConsultation ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("getConsultation")
    class GetConsultation {

        @Test
        @DisplayName("returns consultation by ID")
        void getById() {
            Consultation consultation = buildConsultation(ConsultationStatus.REQUESTED);
            when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(consultation));

            ConsultationResponseDTO result = service.getConsultation(consultationId);

            assertThat(result.getId()).isEqualTo(consultationId);
        }

        @Test
        @DisplayName("throws when not found")
        void throwsWhenNotFound() {
            when(consultationRepository.findById(consultationId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getConsultation(consultationId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── getConsultationsForHospital ──────────────────────────────────────────

    @Nested
    @DisplayName("getConsultationsForHospital")
    class GetConsultationsForHospital {

        @Test
        @DisplayName("filters by status when provided")
        void filtersByStatus() {
            Consultation c = buildConsultation(ConsultationStatus.REQUESTED);
            when(consultationRepository.findByHospital_IdAndStatusOrderByRequestedAtDesc(hospitalId, ConsultationStatus.REQUESTED))
                    .thenReturn(List.of(c));

            List<ConsultationResponseDTO> result = service.getConsultationsForHospital(hospitalId, ConsultationStatus.REQUESTED);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("returns active statuses when status is null")
        void returnsActiveWhenNull() {
            when(consultationRepository.findByHospitalAndStatuses(eq(hospitalId), any()))
                    .thenReturn(List.of());

            List<ConsultationResponseDTO> result = service.getConsultationsForHospital(hospitalId, null);

            assertThat(result).isEmpty();
            verify(consultationRepository).findByHospitalAndStatuses(eq(hospitalId), any());
        }
    }

    // ── getAllConsultations ──────────────────────────────────────────────────

    @Nested
    @DisplayName("getAllConsultations")
    class GetAllConsultations {

        @Test
        @DisplayName("filters by status when provided")
        void filtersByStatus() {
            when(consultationRepository.findByStatusOrderByRequestedAtDesc(ConsultationStatus.COMPLETED))
                    .thenReturn(List.of());

            List<ConsultationResponseDTO> result = service.getAllConsultations(ConsultationStatus.COMPLETED);

            assertThat(result).isEmpty();
            verify(consultationRepository).findByStatusOrderByRequestedAtDesc(ConsultationStatus.COMPLETED);
        }

        @Test
        @DisplayName("returns all when status is null")
        void returnsAllWhenNull() {
            when(consultationRepository.findAllByOrderByRequestedAtDesc()).thenReturn(List.of());

            List<ConsultationResponseDTO> result = service.getAllConsultations(null);

            assertThat(result).isEmpty();
            verify(consultationRepository).findAllByOrderByRequestedAtDesc();
        }
    }

    // ── getConsultationsAssignedTo ───────────────────────────────────────────

    @Nested
    @DisplayName("getConsultationsAssignedTo")
    class GetConsultationsAssignedTo {

        @Test
        @DisplayName("filters by status when provided")
        void filtersByStatus() {
            when(consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(consultantId, ConsultationStatus.IN_PROGRESS))
                    .thenReturn(List.of());

            service.getConsultationsAssignedTo(consultantId, ConsultationStatus.IN_PROGRESS);

            verify(consultationRepository).findByConsultant_IdAndStatusOrderByRequestedAtDesc(consultantId, ConsultationStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("returns all when status is null")
        void returnsAllWhenNull() {
            when(consultationRepository.findByConsultant_IdOrderByRequestedAtDesc(consultantId))
                    .thenReturn(List.of());

            service.getConsultationsAssignedTo(consultantId, null);

            verify(consultationRepository).findByConsultant_IdOrderByRequestedAtDesc(consultantId);
        }
    }

    // ── acknowledgeConsultation ─────────────────────────────────────────────

    @Nested
    @DisplayName("acknowledgeConsultation")
    class AcknowledgeConsultation {

        @Test
        @DisplayName("acknowledges a REQUESTED consultation")
        void acknowledgesRequested() {
            Consultation consultation = buildConsultation(ConsultationStatus.REQUESTED);
            when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(consultation));
            when(staffRepository.findById(consultantId)).thenReturn(Optional.of(consultant));
            when(consultationRepository.save(any(Consultation.class))).thenAnswer(inv -> inv.getArgument(0));

            ConsultationResponseDTO result = service.acknowledgeConsultation(consultationId, consultantId);

            assertThat(result.getStatus()).isEqualTo(ConsultationStatus.ACKNOWLEDGED);
            assertThat(result.getAcknowledgedAt()).isNotNull();
        }

        @Test
        @DisplayName("throws when already acknowledged")
        void throwsWhenAlreadyAcknowledged() {
            Consultation consultation = buildConsultation(ConsultationStatus.ACKNOWLEDGED);
            when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(consultation));

            assertThatThrownBy(() -> service.acknowledgeConsultation(consultationId, consultantId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already been acknowledged");
        }
    }

    // ── updateConsultation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("updateConsultation")
    class UpdateConsultation {

        @Test
        @DisplayName("updates consultant, scheduledAt, and transitions to SCHEDULED")
        void updatesConsultantAndSchedules() {
            Consultation consultation = buildConsultation(ConsultationStatus.ACKNOWLEDGED);
            UUID newConsultantId = UUID.randomUUID();
            Staff newConsultant = new Staff();
            newConsultant.setId(newConsultantId);

            when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(consultation));
            when(staffRepository.findById(newConsultantId)).thenReturn(Optional.of(newConsultant));
            when(consultationRepository.save(any(Consultation.class))).thenAnswer(inv -> inv.getArgument(0));

            ConsultationUpdateDTO updateDTO = ConsultationUpdateDTO.builder()
                    .consultantId(newConsultantId)
                    .scheduledAt(LocalDateTime.now().plusDays(1))
                    .consultantNote("Note")
                    .recommendations("Rest")
                    .followUpRequired(true)
                    .followUpInstructions("Follow up in 2 weeks")
                    .build();

            ConsultationResponseDTO result = service.updateConsultation(consultationId, updateDTO);

            assertThat(result.getStatus()).isEqualTo(ConsultationStatus.SCHEDULED);
            assertThat(result.getConsultantNote()).isEqualTo("Note");
        }

        @Test
        @DisplayName("does not change consultant when same")
        void doesNotChangeConsultantWhenSame() {
            Consultation consultation = buildConsultation(ConsultationStatus.ACKNOWLEDGED);

            when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(consultation));
            when(consultationRepository.save(any(Consultation.class))).thenAnswer(inv -> inv.getArgument(0));

            ConsultationUpdateDTO updateDTO = ConsultationUpdateDTO.builder()
                    .consultantId(consultantId)
                    .build();

            service.updateConsultation(consultationId, updateDTO);

            verify(consultationRepository).save(any(Consultation.class));
        }
    }

    // ── completeConsultation ────────────────────────────────────────────────

    @Nested
    @DisplayName("completeConsultation")
    class CompleteConsultation {

        @Test
        @DisplayName("completes an in-progress consultation")
        void completesInProgress() {
            Consultation consultation = buildConsultation(ConsultationStatus.IN_PROGRESS);
            when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(consultation));
            when(consultationRepository.save(any(Consultation.class))).thenAnswer(inv -> inv.getArgument(0));

            ConsultationUpdateDTO updateDTO = ConsultationUpdateDTO.builder()
                    .consultantNote("Final note")
                    .recommendations("Discharge")
                    .followUpRequired(false)
                    .followUpInstructions("None")
                    .build();

            ConsultationResponseDTO result = service.completeConsultation(consultationId, updateDTO);

            assertThat(result.getStatus()).isEqualTo(ConsultationStatus.COMPLETED);
            assertThat(result.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("throws when already completed")
        void throwsWhenAlreadyCompleted() {
            Consultation consultation = buildConsultation(ConsultationStatus.COMPLETED);
            when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(consultation));

            ConsultationUpdateDTO updateDTO = ConsultationUpdateDTO.builder().build();

            assertThatThrownBy(() -> service.completeConsultation(consultationId, updateDTO))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already completed");
        }

        @Test
        @DisplayName("throws when cancelled")
        void throwsWhenCancelled() {
            Consultation consultation = buildConsultation(ConsultationStatus.CANCELLED);
            when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(consultation));

            ConsultationUpdateDTO updateDTO = ConsultationUpdateDTO.builder().build();

            assertThatThrownBy(() -> service.completeConsultation(consultationId, updateDTO))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Cannot complete a cancelled");
        }
    }

    // ── cancelConsultation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("cancelConsultation")
    class CancelConsultation {

        @Test
        @DisplayName("cancels a requested consultation")
        void cancelsRequested() {
            Consultation consultation = buildConsultation(ConsultationStatus.REQUESTED);
            when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(consultation));
            when(consultationRepository.save(any(Consultation.class))).thenAnswer(inv -> inv.getArgument(0));

            ConsultationResponseDTO result = service.cancelConsultation(consultationId, "No longer needed");

            assertThat(result.getStatus()).isEqualTo(ConsultationStatus.CANCELLED);
            assertThat(result.getCancellationReason()).isEqualTo("No longer needed");
        }

        @Test
        @DisplayName("throws when already completed")
        void throwsWhenCompleted() {
            Consultation consultation = buildConsultation(ConsultationStatus.COMPLETED);
            when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(consultation));

            assertThatThrownBy(() -> service.cancelConsultation(consultationId, "reason"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Cannot cancel a completed");
        }

        @Test
        @DisplayName("throws when already cancelled")
        void throwsWhenAlreadyCancelled() {
            Consultation consultation = buildConsultation(ConsultationStatus.CANCELLED);
            when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(consultation));

            assertThatThrownBy(() -> service.cancelConsultation(consultationId, "reason"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already cancelled");
        }
    }

    // ── getPendingConsultations ──────────────────────────────────────────────

    @Test
    @DisplayName("getPendingConsultations returns REQUESTED and ACKNOWLEDGED")
    void getPendingConsultations() {
        when(consultationRepository.findByHospitalAndStatuses(eq(hospitalId), any()))
                .thenReturn(List.of());

        List<ConsultationResponseDTO> result = service.getPendingConsultations(hospitalId);

        assertThat(result).isEmpty();
        verify(consultationRepository).findByHospitalAndStatuses(eq(hospitalId),
                eq(Arrays.asList(ConsultationStatus.REQUESTED, ConsultationStatus.ACKNOWLEDGED)));
    }

    // ── getConsultationsForPatient ───────────────────────────────────────────

    @Test
    @DisplayName("getConsultationsForPatient delegates to repository")
    void getConsultationsForPatient() {
        Consultation c = buildConsultation(ConsultationStatus.REQUESTED);
        when(consultationRepository.findByPatient_IdOrderByRequestedAtDesc(patientId))
                .thenReturn(List.of(c));

        List<ConsultationResponseDTO> result = service.getConsultationsForPatient(patientId);

        assertThat(result).hasSize(1);
    }

    // ── getConsultationsRequestedBy ─────────────────────────────────────────

    @Test
    @DisplayName("getConsultationsRequestedBy delegates to repository")
    void getConsultationsRequestedBy() {
        when(consultationRepository.findByRequestingProvider_IdOrderByRequestedAtDesc(staffId))
                .thenReturn(List.of());

        List<ConsultationResponseDTO> result = service.getConsultationsRequestedBy(staffId);

        assertThat(result).isEmpty();
    }
}
