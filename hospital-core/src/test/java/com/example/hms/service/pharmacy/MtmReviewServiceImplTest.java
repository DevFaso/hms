package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.MtmReviewStatus;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.pharmacy.MtmReviewMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.MtmReview;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.pharmacy.MtmReviewRequestDTO;
import com.example.hms.payload.dto.pharmacy.MtmReviewResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.MtmReviewRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P-09 follow-up: unit tests for MtmReviewServiceImpl. Covers tenant isolation,
 * polypharmacy auto-detection, MTM_REVIEW_STARTED audit emission, and the
 * intervention-recorded transition that fires on first non-blank intervention summary.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MtmReviewServiceImpl")
class MtmReviewServiceImplTest {

    @Mock private MtmReviewRepository reviewRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private UserRepository userRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private MtmReviewMapper mapper;
    @Mock private RoleValidator roleValidator;
    @Mock private AuditEventLogService auditEventLogService;

    @InjectMocks private MtmReviewServiceImpl service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID reviewId = UUID.randomUUID();

    private Hospital hospital;
    private Patient patient;
    private User user;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(hospitalId);
        patient = new Patient();
        patient.setId(patientId);
        user = new User();
        user.setId(userId);
    }

    private MtmReviewRequestDTO request() {
        return MtmReviewRequestDTO.builder()
                .patientId(patientId)
                .hospitalId(hospitalId)
                .build();
    }

    private Prescription rx(String code, PrescriptionStatus status) {
        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        p.setMedicationCode(code);
        p.setStatus(status);
        return p;
    }

    @Test
    @DisplayName("startReview: rejects when request hospital differs from active hospital context")
    void rejectsCrossTenantHospital() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.startReview(request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("active hospital context");
    }

    @Test
    @DisplayName("startReview: flags polypharmacy when 5+ distinct active drug codes")
    void flagsPolypharmacy() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(
                rx("A", PrescriptionStatus.SIGNED),
                rx("B", PrescriptionStatus.SIGNED),
                rx("C", PrescriptionStatus.TRANSMITTED),
                rx("D", PrescriptionStatus.PARTIALLY_FILLED),
                rx("E", PrescriptionStatus.DISPENSED)
        ));
        when(reviewRepository.save(any(MtmReview.class))).thenAnswer(inv -> {
            MtmReview r = inv.getArgument(0);
            r.setId(reviewId);
            return r;
        });
        when(mapper.toResponseDTO(any(MtmReview.class))).thenReturn(MtmReviewResponseDTO.builder().build());

        service.startReview(request());

        ArgumentCaptor<MtmReview> captor = ArgumentCaptor.forClass(MtmReview.class);
        verify(reviewRepository).save(captor.capture());
        assertThat(captor.getValue().isPolypharmacyAlert()).isTrue();
        assertThat(captor.getValue().getStatus()).isEqualTo(MtmReviewStatus.DRAFT);
    }

    @Test
    @DisplayName("startReview: does NOT flag polypharmacy below the threshold")
    void doesNotFlagBelowThreshold() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(
                rx("A", PrescriptionStatus.SIGNED),
                rx("B", PrescriptionStatus.SIGNED)
        ));
        when(reviewRepository.save(any(MtmReview.class))).thenAnswer(inv -> {
            MtmReview r = inv.getArgument(0);
            r.setId(reviewId);
            return r;
        });
        when(mapper.toResponseDTO(any(MtmReview.class))).thenReturn(MtmReviewResponseDTO.builder().build());

        service.startReview(request());

        ArgumentCaptor<MtmReview> captor = ArgumentCaptor.forClass(MtmReview.class);
        verify(reviewRepository).save(captor.capture());
        assertThat(captor.getValue().isPolypharmacyAlert()).isFalse();
    }

    @Test
    @DisplayName("startReview: emits MTM_REVIEW_STARTED audit event")
    void emitsStartedAudit() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(new ArrayList<>());
        when(reviewRepository.save(any(MtmReview.class))).thenAnswer(inv -> {
            MtmReview r = inv.getArgument(0);
            r.setId(reviewId);
            return r;
        });
        when(mapper.toResponseDTO(any(MtmReview.class))).thenReturn(MtmReviewResponseDTO.builder().build());

        service.startReview(request());

        ArgumentCaptor<AuditEventRequestDTO> captor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(AuditEventType.MTM_REVIEW_STARTED);
        assertThat(captor.getValue().getEntityType()).isEqualTo("MTM_REVIEW");
    }

    @Test
    @DisplayName("startReview: also emits MTM_INTERVENTION_RECORDED when summary present at create")
    void emitsInterventionAuditOnCreateWithSummary() {
        MtmReviewRequestDTO dto = request();
        dto.setInterventionSummary("Switched from drug X to Y due to interaction");

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(new ArrayList<>());
        when(reviewRepository.save(any(MtmReview.class))).thenAnswer(inv -> {
            MtmReview r = inv.getArgument(0);
            r.setId(reviewId);
            return r;
        });
        when(mapper.toResponseDTO(any(MtmReview.class))).thenReturn(MtmReviewResponseDTO.builder().build());

        service.startReview(dto);

        verify(auditEventLogService, times(2)).logEvent(any(AuditEventRequestDTO.class));
    }

    @Test
    @DisplayName("updateReview: emits MTM_INTERVENTION_RECORDED on first non-blank intervention summary")
    void emitsInterventionAuditOnFirstSummary() {
        MtmReview existing = MtmReview.builder()
                .patient(patient).hospital(hospital).pharmacistUser(user)
                .status(MtmReviewStatus.DRAFT)
                .interventionSummary(null) // empty before
                .build();
        existing.setId(reviewId);

        MtmReviewRequestDTO update = request();
        update.setInterventionSummary("Counselled on adherence");

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(existing));
        when(reviewRepository.save(any(MtmReview.class))).thenAnswer(inv -> {
            MtmReview r = inv.getArgument(0);
            if (r.getId() == null) r.setId(reviewId);
            return r;
        });
        when(mapper.toResponseDTO(any(MtmReview.class))).thenReturn(MtmReviewResponseDTO.builder().build());

        service.updateReview(reviewId, update);

        ArgumentCaptor<AuditEventRequestDTO> captor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(AuditEventType.MTM_INTERVENTION_RECORDED);
    }

    @Test
    @DisplayName("updateReview: does NOT re-emit intervention audit when summary already existed")
    void doesNotReEmitWhenSummaryAlreadyPresent() {
        MtmReview existing = MtmReview.builder()
                .patient(patient).hospital(hospital).pharmacistUser(user)
                .status(MtmReviewStatus.DRAFT)
                .interventionSummary("Previous note")
                .build();
        existing.setId(reviewId);

        MtmReviewRequestDTO update = request();
        update.setInterventionSummary("Updated note");

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(existing));
        when(reviewRepository.save(any(MtmReview.class))).thenAnswer(inv -> {
            MtmReview r = inv.getArgument(0);
            if (r.getId() == null) r.setId(reviewId);
            return r;
        });
        when(mapper.toResponseDTO(any(MtmReview.class))).thenReturn(MtmReviewResponseDTO.builder().build());

        service.updateReview(reviewId, update);

        verify(auditEventLogService, never()).logEvent(any(AuditEventRequestDTO.class));
    }

    @Test
    @DisplayName("getReview: hides cross-tenant existence as not-found")
    void getReviewHidesCrossTenant() {
        Hospital otherHospital = new Hospital();
        otherHospital.setId(UUID.randomUUID());
        MtmReview review = MtmReview.builder().hospital(otherHospital).build();
        review.setId(reviewId);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> service.getReview(reviewId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
