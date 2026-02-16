package com.example.hms.service;

import com.example.hms.enums.TreatmentPlanReviewAction;
import com.example.hms.enums.TreatmentPlanStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.TreatmentPlanMapper;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.treatment.TreatmentPlan;
import com.example.hms.model.treatment.TreatmentPlanFollowUp;
import com.example.hms.model.treatment.TreatmentPlanReview;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanFollowUpDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanFollowUpRequestDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanRequestDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanResponseDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanReviewDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanReviewRequestDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.TreatmentPlanFollowUpRepository;
import com.example.hms.repository.TreatmentPlanRepository;
import com.example.hms.repository.TreatmentPlanReviewRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TreatmentPlanServiceImplTest {

    @Mock private TreatmentPlanRepository treatmentPlanRepository;
    @Mock private TreatmentPlanFollowUpRepository treatmentPlanFollowUpRepository;
    @Mock private TreatmentPlanReviewRepository treatmentPlanReviewRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private TreatmentPlanMapper treatmentPlanMapper;

    @InjectMocks private TreatmentPlanServiceImpl service;

    private UUID patientId, hospitalId, encounterId, assignmentId, authorId, supervisingId, signOffId, planId;
    private Patient patient;
    private Hospital hospital;
    private Encounter encounter;
    private UserRoleHospitalAssignment assignment;
    private Staff author, supervising, signOff;
    private TreatmentPlan plan;
    private TreatmentPlanRequestDTO requestDTO;
    private TreatmentPlanResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        encounterId = UUID.randomUUID();
        assignmentId = UUID.randomUUID();
        authorId = UUID.randomUUID();
        supervisingId = UUID.randomUUID();
        signOffId = UUID.randomUUID();
        planId = UUID.randomUUID();

        patient = new Patient();
        patient.setId(patientId);

        hospital = new Hospital();
        hospital.setId(hospitalId);

        encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setHospital(hospital);
        encounter.setPatient(patient);

        assignment = new UserRoleHospitalAssignment();
        assignment.setId(assignmentId);
        assignment.setHospital(hospital);

        author = new Staff();
        author.setId(authorId);
        author.setHospital(hospital);

        supervising = new Staff();
        supervising.setId(supervisingId);
        supervising.setHospital(hospital);

        signOff = new Staff();
        signOff.setId(signOffId);
        signOff.setHospital(hospital);

        plan = TreatmentPlan.builder()
                .patient(patient)
                .hospital(hospital)
                .encounter(encounter)
                .assignment(assignment)
                .author(author)
                .supervisingStaff(supervising)
                .signOffBy(signOff)
                .problemStatement("Pain management")
                .build();
        plan.setId(planId);

        requestDTO = TreatmentPlanRequestDTO.builder()
                .patientId(patientId)
                .hospitalId(hospitalId)
                .encounterId(encounterId)
                .assignmentId(assignmentId)
                .authorStaffId(authorId)
                .supervisingStaffId(supervisingId)
                .signOffStaffId(signOffId)
                .problemStatement("Pain management")
                .build();

        responseDTO = TreatmentPlanResponseDTO.builder()
                .id(planId)
                .patientId(patientId)
                .hospitalId(hospitalId)
                .status(TreatmentPlanStatus.DRAFT)
                .problemStatement("Pain management")
                .build();
    }

    // ---- create ----

    @Test
    void create_success() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(staffRepository.findById(authorId)).thenReturn(Optional.of(author));
        when(staffRepository.findById(supervisingId)).thenReturn(Optional.of(supervising));
        when(staffRepository.findById(signOffId)).thenReturn(Optional.of(signOff));
        when(treatmentPlanMapper.toEntity(eq(requestDTO), eq(patient), any())).thenReturn(plan);
        when(treatmentPlanRepository.save(plan)).thenReturn(plan);
        when(treatmentPlanMapper.toResponseDTO(plan)).thenReturn(responseDTO);

        TreatmentPlanResponseDTO result = service.create(requestDTO);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(planId);
        verify(treatmentPlanRepository).save(plan);
    }

    @Test
    void create_patientNotFound_throws() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(requestDTO))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Patient not found");
    }

    @Test
    void create_hospitalNotFound_throws() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(requestDTO))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Hospital not found");
    }

    @Test
    void create_encounterNotFound_throws() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(requestDTO))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Encounter not found");
    }

    @Test
    void create_assignmentNotFound_throws() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(requestDTO))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Assignment not found");
    }

    @Test
    void create_authorNotFound_throws() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(staffRepository.findById(authorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(requestDTO))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("staff not found");
    }

    @Test
    void create_noEncounter_success() {
        requestDTO.setEncounterId(null);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(staffRepository.findById(authorId)).thenReturn(Optional.of(author));
        when(staffRepository.findById(supervisingId)).thenReturn(Optional.of(supervising));
        when(staffRepository.findById(signOffId)).thenReturn(Optional.of(signOff));
        when(treatmentPlanMapper.toEntity(eq(requestDTO), eq(patient), any())).thenReturn(plan);
        when(treatmentPlanRepository.save(plan)).thenReturn(plan);
        when(treatmentPlanMapper.toResponseDTO(plan)).thenReturn(responseDTO);

        TreatmentPlanResponseDTO result = service.create(requestDTO);
        assertThat(result).isNotNull();
    }

    @Test
    void create_noOptionalStaff_success() {
        requestDTO.setSupervisingStaffId(null);
        requestDTO.setSignOffStaffId(null);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(staffRepository.findById(authorId)).thenReturn(Optional.of(author));
        when(treatmentPlanMapper.toEntity(eq(requestDTO), eq(patient), any())).thenReturn(plan);
        when(treatmentPlanRepository.save(plan)).thenReturn(plan);
        when(treatmentPlanMapper.toResponseDTO(plan)).thenReturn(responseDTO);

        TreatmentPlanResponseDTO result = service.create(requestDTO);
        assertThat(result).isNotNull();
    }

    @Test
    void create_assignmentHospitalMismatch_throws() {
        Hospital otherHospital = new Hospital();
        otherHospital.setId(UUID.randomUUID());
        assignment.setHospital(otherHospital);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(staffRepository.findById(authorId)).thenReturn(Optional.of(author));
        when(staffRepository.findById(supervisingId)).thenReturn(Optional.of(supervising));
        when(staffRepository.findById(signOffId)).thenReturn(Optional.of(signOff));

        assertThatThrownBy(() -> service.create(requestDTO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Assignment hospital mismatch");
    }

    @Test
    void create_authorHospitalMismatch_throws() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        author.setHospital(other);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(staffRepository.findById(authorId)).thenReturn(Optional.of(author));
        when(staffRepository.findById(supervisingId)).thenReturn(Optional.of(supervising));
        when(staffRepository.findById(signOffId)).thenReturn(Optional.of(signOff));

        assertThatThrownBy(() -> service.create(requestDTO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("staff must belong to hospital");
    }

    @Test
    void create_encounterHospitalMismatch_throws() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        encounter.setHospital(other);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(staffRepository.findById(authorId)).thenReturn(Optional.of(author));
        when(staffRepository.findById(supervisingId)).thenReturn(Optional.of(supervising));
        when(staffRepository.findById(signOffId)).thenReturn(Optional.of(signOff));

        assertThatThrownBy(() -> service.create(requestDTO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Encounter hospital mismatch");
    }

    @Test
    void create_encounterPatientMismatch_throws() {
        Patient other = new Patient();
        other.setId(UUID.randomUUID());
        encounter.setPatient(other);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(staffRepository.findById(authorId)).thenReturn(Optional.of(author));
        when(staffRepository.findById(supervisingId)).thenReturn(Optional.of(supervising));
        when(staffRepository.findById(signOffId)).thenReturn(Optional.of(signOff));

        assertThatThrownBy(() -> service.create(requestDTO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same patient");
    }

    @Test
    void create_withFollowUps_success() {
        TreatmentPlanFollowUpRequestDTO fuReq = TreatmentPlanFollowUpRequestDTO.builder()
                .label("Check-up")
                .instructions("Follow up in 2 weeks")
                .dueOn(LocalDate.now().plusWeeks(2))
                .build();
        requestDTO.setFollowUps(List.of(fuReq));

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(staffRepository.findById(authorId)).thenReturn(Optional.of(author));
        when(staffRepository.findById(supervisingId)).thenReturn(Optional.of(supervising));
        when(staffRepository.findById(signOffId)).thenReturn(Optional.of(signOff));
        when(treatmentPlanMapper.toEntity(eq(requestDTO), eq(patient), any())).thenReturn(plan);
        when(treatmentPlanRepository.save(plan)).thenReturn(plan);
        when(treatmentPlanMapper.toResponseDTO(plan)).thenReturn(responseDTO);

        TreatmentPlanResponseDTO result = service.create(requestDTO);
        assertThat(result).isNotNull();
    }

    // ---- update ----

    @Test
    void update_success() {
        when(treatmentPlanRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(staffRepository.findById(authorId)).thenReturn(Optional.of(author));
        when(staffRepository.findById(supervisingId)).thenReturn(Optional.of(supervising));
        when(staffRepository.findById(signOffId)).thenReturn(Optional.of(signOff));
        doNothing().when(treatmentPlanMapper).applyRequest(eq(plan), eq(requestDTO), any());
        when(treatmentPlanRepository.save(plan)).thenReturn(plan);
        when(treatmentPlanMapper.toResponseDTO(plan)).thenReturn(responseDTO);

        TreatmentPlanResponseDTO result = service.update(planId, requestDTO);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(planId);
    }

    @Test
    void update_planNotFound_throws() {
        when(treatmentPlanRepository.findById(planId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(planId, requestDTO))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Treatment plan not found");
    }

    // ---- getById ----

    @Test
    void getById_success() {
        when(treatmentPlanRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(treatmentPlanMapper.toResponseDTO(plan)).thenReturn(responseDTO);

        TreatmentPlanResponseDTO result = service.getById(planId);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(planId);
    }

    @Test
    void getById_notFound_throws() {
        when(treatmentPlanRepository.findById(planId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(planId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Treatment plan not found");
    }

    // ---- listByPatient ----

    @Test
    void listByPatient_success() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<TreatmentPlan> page = new PageImpl<>(List.of(plan));
        when(treatmentPlanRepository.findAllByPatientId(patientId, pageable)).thenReturn(page);
        when(treatmentPlanMapper.toResponseDTO(plan)).thenReturn(responseDTO);

        Page<TreatmentPlanResponseDTO> result = service.listByPatient(patientId, pageable);

        assertThat(result.getContent()).hasSize(1);
    }

    // ---- listByHospital ----

    @Test
    void listByHospital_withStatus_success() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<TreatmentPlan> page = new PageImpl<>(List.of(plan));
        when(treatmentPlanRepository.findAllByHospitalIdAndStatus(hospitalId, TreatmentPlanStatus.DRAFT, pageable))
                .thenReturn(page);
        when(treatmentPlanMapper.toResponseDTO(plan)).thenReturn(responseDTO);

        Page<TreatmentPlanResponseDTO> result = service.listByHospital(hospitalId, TreatmentPlanStatus.DRAFT, pageable);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void listByHospital_noStatus_success() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<TreatmentPlan> page = new PageImpl<>(List.of(plan));
        when(treatmentPlanRepository.findAllByHospitalId(hospitalId, pageable)).thenReturn(page);
        when(treatmentPlanMapper.toResponseDTO(plan)).thenReturn(responseDTO);

        Page<TreatmentPlanResponseDTO> result = service.listByHospital(hospitalId, null, pageable);

        assertThat(result.getContent()).hasSize(1);
    }

    // ---- addFollowUp ----

    @Test
    void addFollowUp_success() {
        TreatmentPlanFollowUpRequestDTO fuReq = TreatmentPlanFollowUpRequestDTO.builder()
                .label("Lab Review")
                .instructions("Review blood work")
                .dueOn(LocalDate.now().plusDays(7))
                .build();
        TreatmentPlanFollowUp followUp = TreatmentPlanFollowUp.builder()
                .treatmentPlan(plan)
                .label("Lab Review")
                .instructions("Review blood work")
                .build();
        followUp.setId(UUID.randomUUID());
        TreatmentPlanFollowUpDTO fuDTO = new TreatmentPlanFollowUpDTO();

        when(treatmentPlanRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(treatmentPlanFollowUpRepository.save(any(TreatmentPlanFollowUp.class))).thenReturn(followUp);
        when(treatmentPlanMapper.toFollowUpDTO(followUp)).thenReturn(fuDTO);

        TreatmentPlanFollowUpDTO result = service.addFollowUp(planId, fuReq);

        assertThat(result).isNotNull();
        verify(treatmentPlanFollowUpRepository).save(any(TreatmentPlanFollowUp.class));
    }

    @Test
    void addFollowUp_planNotFound_throws() {
        when(treatmentPlanRepository.findById(planId)).thenReturn(Optional.empty());

        TreatmentPlanFollowUpRequestDTO request = new TreatmentPlanFollowUpRequestDTO();
        assertThatThrownBy(() -> service.addFollowUp(planId, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void addFollowUp_withAssignedStaff_success() {
        UUID staffId = UUID.randomUUID();
        Staff assignedStaff = new Staff();
        assignedStaff.setId(staffId);

        TreatmentPlanFollowUpRequestDTO fuReq = TreatmentPlanFollowUpRequestDTO.builder()
                .label("Check")
                .assignedStaffId(staffId)
                .build();
        TreatmentPlanFollowUp followUp = TreatmentPlanFollowUp.builder()
                .treatmentPlan(plan)
                .label("Check")
                .assignedStaff(assignedStaff)
                .build();
        followUp.setId(UUID.randomUUID());
        TreatmentPlanFollowUpDTO fuDTO = new TreatmentPlanFollowUpDTO();

        when(treatmentPlanRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(assignedStaff));
        when(treatmentPlanFollowUpRepository.save(any(TreatmentPlanFollowUp.class))).thenReturn(followUp);
        when(treatmentPlanMapper.toFollowUpDTO(followUp)).thenReturn(fuDTO);

        TreatmentPlanFollowUpDTO result = service.addFollowUp(planId, fuReq);
        assertThat(result).isNotNull();
    }

    // ---- updateFollowUp ----

    @Test
    void updateFollowUp_success() {
        UUID followUpId = UUID.randomUUID();
        TreatmentPlanFollowUp followUp = TreatmentPlanFollowUp.builder()
                .treatmentPlan(plan)
                .label("Old")
                .build();
        followUp.setId(followUpId);
        TreatmentPlanFollowUpRequestDTO fuReq = TreatmentPlanFollowUpRequestDTO.builder()
                .label("New Label")
                .instructions("New instructions")
                .dueOn(LocalDate.now().plusMonths(1))
                .build();
        TreatmentPlanFollowUpDTO fuDTO = new TreatmentPlanFollowUpDTO();

        when(treatmentPlanFollowUpRepository.findByIdAndTreatmentPlanId(followUpId, planId))
                .thenReturn(Optional.of(followUp));
        when(treatmentPlanFollowUpRepository.save(followUp)).thenReturn(followUp);
        when(treatmentPlanMapper.toFollowUpDTO(followUp)).thenReturn(fuDTO);

        TreatmentPlanFollowUpDTO result = service.updateFollowUp(planId, followUpId, fuReq);

        assertThat(result).isNotNull();
        assertThat(followUp.getLabel()).isEqualTo("New Label");
    }

    @Test
    void updateFollowUp_notFound_throws() {
        UUID followUpId = UUID.randomUUID();
        when(treatmentPlanFollowUpRepository.findByIdAndTreatmentPlanId(followUpId, planId))
                .thenReturn(Optional.empty());

        TreatmentPlanFollowUpRequestDTO request = new TreatmentPlanFollowUpRequestDTO();
        assertThatThrownBy(() -> service.updateFollowUp(planId, followUpId, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("follow-up not found");
    }

    // ---- addReview ----

    @Test
    void addReview_success() {
        UUID reviewerId = UUID.randomUUID();
        Staff reviewer = new Staff();
        reviewer.setId(reviewerId);

        TreatmentPlanReviewRequestDTO reviewReq = TreatmentPlanReviewRequestDTO.builder()
                .reviewerStaffId(reviewerId)
                .action(TreatmentPlanReviewAction.APPROVED)
                .comment("Looks good")
                .build();
        TreatmentPlanReview review = TreatmentPlanReview.builder()
                .treatmentPlan(plan)
                .reviewer(reviewer)
                .action(TreatmentPlanReviewAction.APPROVED)
                .comment("Looks good")
                .build();
        review.setId(UUID.randomUUID());
        TreatmentPlanReviewDTO reviewDTO = new TreatmentPlanReviewDTO();

        when(treatmentPlanRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(staffRepository.findById(reviewerId)).thenReturn(Optional.of(reviewer));
        when(treatmentPlanReviewRepository.save(any(TreatmentPlanReview.class))).thenReturn(review);
        when(treatmentPlanMapper.toReviewDTO(review)).thenReturn(reviewDTO);

        TreatmentPlanReviewDTO result = service.addReview(planId, reviewReq);

        assertThat(result).isNotNull();
        verify(treatmentPlanReviewRepository).save(any(TreatmentPlanReview.class));
    }

    @Test
    void addReview_reviewerNotFound_throws() {
        UUID reviewerId = UUID.randomUUID();
        TreatmentPlanReviewRequestDTO reviewReq = TreatmentPlanReviewRequestDTO.builder()
                .reviewerStaffId(reviewerId)
                .action(TreatmentPlanReviewAction.APPROVED)
                .build();

        when(treatmentPlanRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(staffRepository.findById(reviewerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addReview(planId, reviewReq))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("staff not found");
    }

    @Test
    void addReview_planNotFound_throws() {
        when(treatmentPlanRepository.findById(planId)).thenReturn(Optional.empty());

        TreatmentPlanReviewRequestDTO request = new TreatmentPlanReviewRequestDTO();
        assertThatThrownBy(() -> service.addReview(planId, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- create with follow-ups that have assigned staff ----

    @Test
    void create_withFollowUps_assignedStaff_success() {
        UUID fuStaffId = UUID.randomUUID();
        Staff fuStaff = new Staff();
        fuStaff.setId(fuStaffId);

        TreatmentPlanFollowUpRequestDTO fuReq = TreatmentPlanFollowUpRequestDTO.builder()
                .label("Blood work")
                .assignedStaffId(fuStaffId)
                .build();
        requestDTO.setFollowUps(List.of(fuReq));

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(staffRepository.findById(authorId)).thenReturn(Optional.of(author));
        when(staffRepository.findById(supervisingId)).thenReturn(Optional.of(supervising));
        when(staffRepository.findById(signOffId)).thenReturn(Optional.of(signOff));
        when(staffRepository.findAllById(Set.of(fuStaffId))).thenReturn(List.of(fuStaff));
        when(treatmentPlanMapper.toEntity(eq(requestDTO), eq(patient), any())).thenReturn(plan);
        when(treatmentPlanRepository.save(plan)).thenReturn(plan);
        when(treatmentPlanMapper.toResponseDTO(plan)).thenReturn(responseDTO);

        TreatmentPlanResponseDTO result = service.create(requestDTO);
        assertThat(result).isNotNull();
    }

    @Test
    void create_withFollowUps_staffNotFound_throws() {
        UUID fuStaffId = UUID.randomUUID();

        TreatmentPlanFollowUpRequestDTO fuReq = TreatmentPlanFollowUpRequestDTO.builder()
                .label("Blood work")
                .assignedStaffId(fuStaffId)
                .build();
        requestDTO.setFollowUps(List.of(fuReq));

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(staffRepository.findById(authorId)).thenReturn(Optional.of(author));
        when(staffRepository.findById(supervisingId)).thenReturn(Optional.of(supervising));
        when(staffRepository.findById(signOffId)).thenReturn(Optional.of(signOff));
        when(staffRepository.findAllById(Set.of(fuStaffId))).thenReturn(List.of());
        when(treatmentPlanMapper.toEntity(eq(requestDTO), eq(patient), any())).thenReturn(plan);

        assertThatThrownBy(() -> service.create(requestDTO))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Staff not found");
    }

    // ---- supervising staff hospital mismatch ----

    @Test
    void create_supervisingStaffHospitalMismatch_throws() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        supervising.setHospital(other);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(staffRepository.findById(authorId)).thenReturn(Optional.of(author));
        when(staffRepository.findById(supervisingId)).thenReturn(Optional.of(supervising));
        when(staffRepository.findById(signOffId)).thenReturn(Optional.of(signOff));

        assertThatThrownBy(() -> service.create(requestDTO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("staff must belong to hospital");
    }
}
