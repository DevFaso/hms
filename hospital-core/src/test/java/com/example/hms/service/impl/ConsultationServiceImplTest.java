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
import com.example.hms.repository.ConsultationRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConsultationServiceImplTest {

    @Mock private ConsultationRepository consultationRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private EncounterRepository encounterRepository;

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
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(consultationRepository.save(any())).thenAnswer(i -> { Consultation c = i.getArgument(0); c.setId(consultationId); return c; });
        ConsultationResponseDTO result = service.createConsultation(r, staffId);
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(ConsultationStatus.REQUESTED);
    }

    @Test void createConsultation_patientNotFound() {
        ConsultationRequestDTO r = new ConsultationRequestDTO();
        r.setPatientId(patientId); r.setHospitalId(hospitalId); r.setUrgency(ConsultationUrgency.URGENT);
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());
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
        when(consultationRepository.findByHospitalAndStatuses(eq(hospitalId), any())).thenReturn(List.of());
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
        ConsultationUpdateDTO u = new ConsultationUpdateDTO(); u.setConsultantNote("All good");
        when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(buildConsultation(ConsultationStatus.IN_PROGRESS)));
        when(consultationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        ConsultationResponseDTO r = service.completeConsultation(consultationId, u);
        assertThat(r.getStatus()).isEqualTo(ConsultationStatus.COMPLETED);
        assertThat(r.getConsultantNote()).isEqualTo("All good");
    }

    @Test void completeConsultation_alreadyCompleted() {
        when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(buildConsultation(ConsultationStatus.COMPLETED)));
        ConsultationUpdateDTO dto = new ConsultationUpdateDTO();
        assertThatThrownBy(() -> service.completeConsultation(consultationId, dto)).isInstanceOf(BusinessException.class);
    }

    @Test void completeConsultation_cancelled() {
        when(consultationRepository.findById(consultationId)).thenReturn(Optional.of(buildConsultation(ConsultationStatus.CANCELLED)));
        ConsultationUpdateDTO dto = new ConsultationUpdateDTO();
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
}
