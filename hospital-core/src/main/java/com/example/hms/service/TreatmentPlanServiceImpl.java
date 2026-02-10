package com.example.hms.service;

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
import com.example.hms.payload.dto.clinical.treatment.*;
import com.example.hms.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class TreatmentPlanServiceImpl implements TreatmentPlanService {

    private static final String AUTHOR_LABEL = "Author";
    private static final String STAFF_LABEL = "Staff";
    private static final String REVIEWER_LABEL = "Reviewer";

    private final TreatmentPlanRepository treatmentPlanRepository;
    private final TreatmentPlanFollowUpRepository treatmentPlanFollowUpRepository;
    private final TreatmentPlanReviewRepository treatmentPlanReviewRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final EncounterRepository encounterRepository;
    private final StaffRepository staffRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final TreatmentPlanMapper treatmentPlanMapper;

    @Override
    public TreatmentPlanResponseDTO create(TreatmentPlanRequestDTO requestDTO) {
        Patient patient = fetchPatient(requestDTO.getPatientId());
        Hospital hospital = fetchHospital(requestDTO.getHospitalId());
        Encounter encounter = resolveEncounter(requestDTO.getEncounterId());
        UserRoleHospitalAssignment assignment = fetchAssignment(requestDTO.getAssignmentId());
    Staff author = fetchStaff(requestDTO.getAuthorStaffId(), AUTHOR_LABEL);
        Staff supervising = fetchOptionalStaff(requestDTO.getSupervisingStaffId());
        Staff signOff = fetchOptionalStaff(requestDTO.getSignOffStaffId());

        validateHospitalContext(hospital, assignment, author, supervising, signOff);
        validateEncounterContext(encounter, hospital, patient);

        var context = new TreatmentPlanMapper.TreatmentPlanContext(hospital, encounter, assignment, author, supervising, signOff);
        TreatmentPlan plan = treatmentPlanMapper.toEntity(requestDTO, patient, context);
        applyFollowUps(plan, requestDTO.getFollowUps());
        TreatmentPlan persisted = treatmentPlanRepository.save(plan);
        return treatmentPlanMapper.toResponseDTO(persisted);
    }

    @Override
    public TreatmentPlanResponseDTO update(UUID id, TreatmentPlanRequestDTO requestDTO) {
        TreatmentPlan plan = getPlanOrThrow(id);
        Patient patient = fetchPatient(requestDTO.getPatientId());
        Hospital hospital = fetchHospital(requestDTO.getHospitalId());
        Encounter encounter = resolveEncounter(requestDTO.getEncounterId());
        UserRoleHospitalAssignment assignment = fetchAssignment(requestDTO.getAssignmentId());
    Staff author = fetchStaff(requestDTO.getAuthorStaffId(), AUTHOR_LABEL);
        Staff supervising = fetchOptionalStaff(requestDTO.getSupervisingStaffId());
        Staff signOff = fetchOptionalStaff(requestDTO.getSignOffStaffId());

        validateHospitalContext(hospital, assignment, author, supervising, signOff);
        validateEncounterContext(encounter, hospital, patient);

        var context = new TreatmentPlanMapper.TreatmentPlanContext(hospital, encounter, assignment, author, supervising, signOff);
        plan.setPatient(patient);
        treatmentPlanMapper.applyRequest(plan, requestDTO, context);
        applyFollowUps(plan, requestDTO.getFollowUps());
        TreatmentPlan saved = treatmentPlanRepository.save(plan);
        return treatmentPlanMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public TreatmentPlanResponseDTO getById(UUID id) {
        return treatmentPlanMapper.toResponseDTO(getPlanOrThrow(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TreatmentPlanResponseDTO> listByPatient(UUID patientId, Pageable pageable) {
        return treatmentPlanRepository.findAllByPatientId(patientId, pageable)
            .map(treatmentPlanMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TreatmentPlanResponseDTO> listByHospital(UUID hospitalId, TreatmentPlanStatus status, Pageable pageable) {
        Page<TreatmentPlan> page = (status != null)
            ? treatmentPlanRepository.findAllByHospitalIdAndStatus(hospitalId, status, pageable)
            : treatmentPlanRepository.findAllByHospitalId(hospitalId, pageable);
        return page.map(treatmentPlanMapper::toResponseDTO);
    }

    @Override
    public TreatmentPlanFollowUpDTO addFollowUp(UUID planId, TreatmentPlanFollowUpRequestDTO requestDTO) {
        TreatmentPlan plan = getPlanOrThrow(planId);
        TreatmentPlanFollowUp followUp = TreatmentPlanFollowUp.builder()
            .treatmentPlan(plan)
            .label(requestDTO.getLabel())
            .instructions(requestDTO.getInstructions())
            .dueOn(requestDTO.getDueOn())
            .assignedStaff(optionalStaff(requestDTO.getAssignedStaffId()))
            .build();
        TreatmentPlanFollowUp saved = treatmentPlanFollowUpRepository.save(followUp);
        plan.addFollowUp(saved);
        return treatmentPlanMapper.toFollowUpDTO(saved);
    }

    @Override
    public TreatmentPlanFollowUpDTO updateFollowUp(UUID planId, UUID followUpId, TreatmentPlanFollowUpRequestDTO requestDTO) {
        TreatmentPlanFollowUp followUp = treatmentPlanFollowUpRepository.findByIdAndTreatmentPlanId(followUpId, planId)
            .orElseThrow(() -> new ResourceNotFoundException("Treatment plan follow-up not found"));
        followUp.setLabel(requestDTO.getLabel());
        followUp.setInstructions(requestDTO.getInstructions());
        followUp.setDueOn(requestDTO.getDueOn());
        followUp.setAssignedStaff(optionalStaff(requestDTO.getAssignedStaffId()));
        TreatmentPlanFollowUp saved = treatmentPlanFollowUpRepository.save(followUp);
        return treatmentPlanMapper.toFollowUpDTO(saved);
    }

    @Override
    public TreatmentPlanReviewDTO addReview(UUID planId, TreatmentPlanReviewRequestDTO requestDTO) {
        TreatmentPlan plan = getPlanOrThrow(planId);
    Staff reviewer = fetchStaff(requestDTO.getReviewerStaffId(), REVIEWER_LABEL);
        TreatmentPlanReview review = TreatmentPlanReview.builder()
            .treatmentPlan(plan)
            .reviewer(reviewer)
            .action(requestDTO.getAction())
            .comment(requestDTO.getComment())
            .build();
        TreatmentPlanReview saved = treatmentPlanReviewRepository.save(review);
        plan.addReview(saved);
        return treatmentPlanMapper.toReviewDTO(saved);
    }

    private TreatmentPlan getPlanOrThrow(UUID id) {
        return treatmentPlanRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Treatment plan not found"));
    }

    private Patient fetchPatient(UUID id) {
        return patientRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));
    }

    private Hospital fetchHospital(UUID id) {
        return hospitalRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));
    }

    private Encounter resolveEncounter(UUID encounterId) {
        if (encounterId == null) {
            return null;
        }
        return encounterRepository.findById(encounterId)
            .orElseThrow(() -> new ResourceNotFoundException("Encounter not found"));
    }

    private UserRoleHospitalAssignment fetchAssignment(UUID assignmentId) {
        return assignmentRepository.findById(assignmentId)
            .orElseThrow(() -> new ResourceNotFoundException("Assignment not found"));
    }

    private Staff fetchStaff(UUID staffId, String label) {
        return staffRepository.findById(staffId)
            .orElseThrow(() -> new ResourceNotFoundException(label + " staff not found"));
    }

    private Staff fetchOptionalStaff(UUID staffId) {
        if (staffId == null) {
            return null;
        }
        return fetchStaff(staffId, STAFF_LABEL);
    }

    private Staff optionalStaff(UUID staffId) {
        return staffId == null ? null : fetchStaff(staffId, STAFF_LABEL);
    }

    private void applyFollowUps(TreatmentPlan plan, List<TreatmentPlanFollowUpRequestDTO> followUps) {
        plan.getFollowUps().clear();
        if (CollectionUtils.isEmpty(followUps)) {
            return;
        }
        Map<UUID, Staff> staffMap = resolveStaffMap(
            followUps.stream()
                .map(TreatmentPlanFollowUpRequestDTO::getAssignedStaffId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet())
        );
        List<TreatmentPlanFollowUp> entities = followUps.stream()
            .map(req -> TreatmentPlanFollowUp.builder()
                .label(req.getLabel())
                .instructions(req.getInstructions())
                .dueOn(req.getDueOn())
                .assignedStaff(req.getAssignedStaffId() != null ? staffMap.get(req.getAssignedStaffId()) : null)
                .build())
            .collect(Collectors.toList());
        entities.forEach(plan::addFollowUp);
    }

    private Map<UUID, Staff> resolveStaffMap(Set<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Staff> staffList = staffRepository.findAllById(ids);
        Map<UUID, Staff> map = staffList.stream()
            .collect(Collectors.toMap(Staff::getId, Function.identity()));
        ids.forEach(id -> {
            if (!map.containsKey(id)) {
                throw new ResourceNotFoundException("Staff not found");
            }
        });
        return map;
    }

    private void validateHospitalContext(Hospital hospital,
                                         UserRoleHospitalAssignment assignment,
                                         Staff author,
                                         Staff supervising,
                                         Staff signOff) {
        UUID hospitalId = hospital.getId();
        if (assignment.getHospital() == null || !hospitalId.equals(assignment.getHospital().getId())) {
            throw new IllegalStateException("Assignment hospital mismatch");
        }
        ensureStaffInHospital(author, hospitalId, AUTHOR_LABEL);
        if (supervising != null) {
            ensureStaffInHospital(supervising, hospitalId, "Supervising");
        }
        if (signOff != null) {
            ensureStaffInHospital(signOff, hospitalId, "Sign-off");
        }
    }

    private void ensureStaffInHospital(Staff staff, UUID hospitalId, String label) {
        if (staff.getHospital() == null || !hospitalId.equals(staff.getHospital().getId())) {
            throw new IllegalStateException(label + " staff must belong to hospital context");
        }
    }

    private void validateEncounterContext(Encounter encounter, Hospital hospital, Patient patient) {
        if (encounter == null) {
            return;
        }
        if (encounter.getHospital() == null || !hospital.getId().equals(encounter.getHospital().getId())) {
            throw new IllegalStateException("Encounter hospital mismatch");
        }
        if (encounter.getPatient() == null || !patient.getId().equals(encounter.getPatient().getId())) {
            throw new IllegalStateException("Encounter must reference the same patient");
        }
    }
}
