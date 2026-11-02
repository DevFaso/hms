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
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class TreatmentPlanServiceImpl implements TreatmentPlanService {

    /**
     * Self-reference for proxy-routed internal calls
     * ({@link #listAll(TreatmentPlanStatus, Pageable)}
     * → {@link #listByHospital(UUID, TreatmentPlanStatus, Pageable)}).
     * Sonar S6809 — see PatientServiceImpl.setSelf for the full
     * rationale and pattern docstring.
     */
    private TreatmentPlanService self;

    @Autowired
    public void setSelf(@Lazy TreatmentPlanService self) {
        this.self = self;
    }

    private static final String AUTHOR_LABEL = "Author";

    private final TreatmentPlanRepository treatmentPlanRepository;
    private final TreatmentPlanFollowUpRepository treatmentPlanFollowUpRepository;
    private final TreatmentPlanReviewRepository treatmentPlanReviewRepository;
    private final PatientRepository patientRepository;
    private final com.example.hms.service.support.PatientRegistrationGuard patientRegistrationGuard;
    private final HospitalRepository hospitalRepository;
    private final EncounterRepository encounterRepository;
    private final StaffRepository staffRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final TreatmentPlanMapper treatmentPlanMapper;
    private final com.example.hms.utility.RoleValidator roleValidator;

    @Override
    public TreatmentPlanResponseDTO create(TreatmentPlanRequestDTO requestDTO) {
        Patient patient = fetchPatient(requestDTO.getPatientId());
        Hospital hospital = fetchHospital(requestDTO.getHospitalId());
        Encounter encounter = resolveEncounter(requestDTO.getEncounterId());
    Staff author = fetchStaff(requestDTO.getAuthorStaffId());
        UserRoleHospitalAssignment assignment = resolveAssignment(requestDTO.getAssignmentId(), author);
        Staff supervising = fetchOptionalStaff(requestDTO.getSupervisingStaffId());
        Staff signOff = fetchOptionalStaff(requestDTO.getSignOffStaffId());

        validateHospitalContext(hospital, assignment, author, supervising, signOff);
        // QA D503 class: the author is the caller, not a colleague named in the body.
        com.example.hms.security.CallerIdentity.requireCallerUnlessSuperAdmin(author);
        // QA D1127: a plan is not written straight as APPROVED; another clinician approves it.
        requireNotApprovedByWrite(null, requestDTO.getStatus());
        validateEncounterContext(encounter, hospital, patient);
        // QA D401: the patient must be registered at the hospital the row is written in.
        patientRegistrationGuard.requireRegisteredAt(patient.getId(), hospital.getId());

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
    Staff author = fetchStaff(requestDTO.getAuthorStaffId());
        UserRoleHospitalAssignment assignment = resolveAssignment(requestDTO.getAssignmentId(), author);
        Staff supervising = fetchOptionalStaff(requestDTO.getSupervisingStaffId());
        Staff signOff = fetchOptionalStaff(requestDTO.getSignOffStaffId());

        validateHospitalContext(hospital, assignment, author, supervising, signOff);
        // QA D503 class: an edit keeps the plan's author; naming a new one must name the caller.
        if (plan.getAuthor() == null || !java.util.Objects.equals(plan.getAuthor().getId(), author.getId())) {
            com.example.hms.security.CallerIdentity.requireCallerUnlessSuperAdmin(author);
        }
        // QA D930 class: sending back the plan's own authorStaffId skipped the
        // check above, so any colleague could rewrite the plan. The plan is
        // rewritten by its author only (or a super-admin in global view).
        if (plan.getAuthor() != null) {
            // QA D1112: the authorship rule, not "act in your own name".
            com.example.hms.security.CallerIdentity.requireAuthorUnlessSuperAdmin(plan.getAuthor(),
                "treatmentPlan.authorOnly");
        }
        // QA D1127: the edit is the author's, so it never moves the plan to APPROVED.
        requireNotApprovedByWrite(plan.getStatus(), requestDTO.getStatus());
        // QA D1066 class (round 21, with D1162): a cancelled plan is closed to edits too.
        com.example.hms.service.support.CancelledOrders.requireNotCancelled(plan.getStatus(),
            "treatmentPlan.cancelled.locked");
        validateEncounterContext(encounter, hospital, patient);
        // QA D401: the patient must be registered at the hospital the row is written in.
        patientRegistrationGuard.requireRegisteredAt(patient.getId(), hospital.getId());

        var context = new TreatmentPlanMapper.TreatmentPlanContext(hospital, encounter, assignment, author, supervising, signOff);
        com.example.hms.enums.TreatmentPlanStatus statusBefore = plan.getStatus();
        java.util.List<Object> contentBefore = clinicalContent(plan);
        plan.setPatient(patient);
        treatmentPlanMapper.applyRequest(plan, requestDTO, context);
        // QA D1162 (round 21, user decision): the approval was given to the
        // plan as it was. An edit of its content sends it back for review; it
        // used to stay APPROVED under the colleague's approval.
        plan.setStatus(statusAfterEdit(statusBefore, !contentBefore.equals(clinicalContent(plan)), plan.getStatus()));
        applyFollowUps(plan, requestDTO.getFollowUps());
        TreatmentPlan saved = treatmentPlanRepository.save(plan);
        return treatmentPlanMapper.toResponseDTO(saved);
    }

    /**
     * QA D1162: an APPROVED plan whose clinical content an edit changed goes
     * back to IN_REVIEW, unless the same edit archives or cancels it. An edit
     * that echoes the approved content unchanged keeps the approval.
     */
    static com.example.hms.enums.TreatmentPlanStatus statusAfterEdit(
            com.example.hms.enums.TreatmentPlanStatus before, boolean contentChanged,
            com.example.hms.enums.TreatmentPlanStatus after) {
        if (before == com.example.hms.enums.TreatmentPlanStatus.APPROVED && contentChanged
                && after == com.example.hms.enums.TreatmentPlanStatus.APPROVED) {
            return com.example.hms.enums.TreatmentPlanStatus.IN_REVIEW;
        }
        return after;
    }

    /** What a reviewer approved: the patient, the visit and every clinical field of the plan. */
    private static java.util.List<Object> clinicalContent(TreatmentPlan plan) {
        return java.util.Arrays.asList(
            plan.getPatient() != null ? plan.getPatient().getId() : null,
            plan.getEncounter() != null ? plan.getEncounter().getId() : null,
            plan.getProblemStatement(),
            plan.getTherapeuticGoalsJson(),
            plan.getMedicationPlanJson(),
            plan.getLifestylePlanJson(),
            plan.getTimelineSummary(),
            plan.getFollowUpSummary(),
            plan.getReferralPlanJson(),
            plan.getResponsiblePartiesJson(),
            plan.getTimelineStartDate(),
            plan.getTimelineReviewDate());
    }

    @Override
    @Transactional(readOnly = true)
    public TreatmentPlanResponseDTO getById(UUID id) {
        TreatmentPlan plan = getPlanOrThrow(id);
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && plan.getHospital() != null
                && !activeHospitalId.equals(plan.getHospital().getId())) {
            throw new ResourceNotFoundException("treatmentPlan.notFound", id);
        }
        return treatmentPlanMapper.toResponseDTO(plan);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TreatmentPlanResponseDTO> listForPortalPatient(UUID patientId, Pageable pageable) {
        return treatmentPlanRepository.findAllByPatientId(patientId, pageable)
            .map(treatmentPlanMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TreatmentPlanResponseDTO> listByPatient(UUID patientId, Pageable pageable) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null) {
            // Filter patient results by hospital at the DB level
            return treatmentPlanRepository.findAllByPatientIdAndHospitalId(patientId, activeHospitalId, pageable)
                .map(treatmentPlanMapper::toResponseDTO);
        }
        return treatmentPlanRepository.findAllByPatientId(patientId, pageable)
            .map(treatmentPlanMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TreatmentPlanResponseDTO> listByHospital(UUID hospitalId, TreatmentPlanStatus status, Pageable pageable) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        UUID effectiveHospitalId = activeHospitalId != null ? activeHospitalId : hospitalId;
        Page<TreatmentPlan> page = (status != null)
            ? treatmentPlanRepository.findAllByHospitalIdAndStatus(effectiveHospitalId, status, pageable)
            : treatmentPlanRepository.findAllByHospitalId(effectiveHospitalId, pageable);
        return page.map(treatmentPlanMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TreatmentPlanResponseDTO> listAll(TreatmentPlanStatus status, Pageable pageable) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null) {
            // Sonar S6809: route through the proxy so listByHospital's
            // own @Transactional(readOnly=true) is honored.
            return self.listByHospital(activeHospitalId, status, pageable);
        }
        Page<TreatmentPlan> page = (status != null)
            ? treatmentPlanRepository.findAllByStatus(status, pageable)
            : treatmentPlanRepository.findAll(pageable);
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
            .orElseThrow(() -> new ResourceNotFoundException("treatmentPlan.followUp.notFound", followUpId));
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
    Staff reviewer = fetchStaff(requestDTO.getReviewerStaffId());
        // QA D503 class: the reviewer is the signed-in clinician, not a name in the body.
        com.example.hms.security.CallerIdentity.requireCallerUnlessSuperAdmin(reviewer);
        // QA D955 class: a plan is approved or signed off by someone other than its author.
        boolean approves = requestDTO.getAction() == com.example.hms.enums.TreatmentPlanReviewAction.APPROVED
            || requestDTO.getAction() == com.example.hms.enums.TreatmentPlanReviewAction.SIGNED_OFF;
        if (approves && plan.getAuthor() != null && (java.util.Objects.equals(plan.getAuthor().getId(), reviewer.getId())
                || com.example.hms.security.CallerIdentity.sameUser(plan.getAuthor(), reviewer))) {
            throw new com.example.hms.exception.ClientSafeAccessDeniedException(
                com.example.hms.utility.MessageUtil.resolveOrRaw("treatmentPlan.review.notAuthor"));
        }
        // QA D1066 class (round 20, D1127): a review moves the status now, so a
        // cancelled plan is refused before anything is written; it is never revived.
        com.example.hms.service.support.CancelledOrders.requireNotCancelled(plan.getStatus(),
            "treatmentPlan.cancelled.locked");
        TreatmentPlanReview review = TreatmentPlanReview.builder()
            .treatmentPlan(plan)
            .reviewer(reviewer)
            .action(requestDTO.getAction())
            .comment(requestDTO.getComment())
            .build();
        TreatmentPlanReview saved = treatmentPlanReviewRepository.save(review);
        plan.addReview(saved);
        // QA D1127: the review by another clinician is the one road to APPROVED
        // (the create and the edit refuse it); a request for changes sends the
        // plan back to its author.
        com.example.hms.enums.TreatmentPlanStatus reviewed = statusAfterReview(requestDTO.getAction());
        // QA D1066 class: nor does it reopen an archived plan.
        if (reviewed != null && plan.getStatus() != com.example.hms.enums.TreatmentPlanStatus.ARCHIVED) {
            plan.setStatus(reviewed);
            treatmentPlanRepository.save(plan);
        }
        return treatmentPlanMapper.toReviewDTO(saved);
    }

    /**
     * QA D1127 (round 20): a treatment plan's author could approve it himself
     * by creating it as APPROVED or by an edit to APPROVED, around the
     * "approved by another clinician" rule the review enforces (QA D955). The
     * create and the edit are the author's writes, so neither moves a plan
     * into APPROVED; a plan that is already APPROVED keeps its status on an
     * edit that does not touch it.
     */
    static void requireNotApprovedByWrite(com.example.hms.enums.TreatmentPlanStatus current,
                                          com.example.hms.enums.TreatmentPlanStatus requested) {
        if (requested == com.example.hms.enums.TreatmentPlanStatus.APPROVED
                && current != com.example.hms.enums.TreatmentPlanStatus.APPROVED) {
            throw new com.example.hms.exception.BusinessException("treatmentPlan.approve.byReviewOnly");
        }
    }

    /** The plan status a review moves to, or null when the review leaves it as it is. */
    static com.example.hms.enums.TreatmentPlanStatus statusAfterReview(
            com.example.hms.enums.TreatmentPlanReviewAction action) {
        if (action == null) {
            return null;
        }
        return switch (action) {
            case APPROVED, SIGNED_OFF -> com.example.hms.enums.TreatmentPlanStatus.APPROVED;
            case CHANGES_REQUESTED, SENT_BACK -> com.example.hms.enums.TreatmentPlanStatus.REVISIONS_REQUIRED;
        };
    }

    private TreatmentPlan getPlanOrThrow(UUID id) {
        TreatmentPlan plan = treatmentPlanRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("treatmentPlan.notFound", id));
        // QA r5 tenancy sweep: every write (update, follow-ups, reviews) loads here.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(plan.getHospital(), "treatmentPlan.notFound", id);
        return plan;
    }

    private Patient fetchPatient(UUID id) {
        return patientRepository.findByIdUnscoped(id)
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", id));
    }

    private Hospital fetchHospital(UUID id) {
        Hospital hospital = hospitalRepository.findClinicalById(id)
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", id));
        // QA r5 tenancy sweep: a plan is written at the caller's acting hospital; the body's id is a claim.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(hospital, "hospital.notFound", id);
        return hospital;
    }

    private Encounter resolveEncounter(UUID encounterId) {
        if (encounterId == null) {
            return null;
        }
        return encounterRepository.findById(encounterId)
            .orElseThrow(() -> new ResourceNotFoundException("encounter.notfound", encounterId));
    }

    private UserRoleHospitalAssignment fetchAssignment(UUID assignmentId) {
        return assignmentRepository.findById(assignmentId)
            .orElseThrow(() -> new ResourceNotFoundException("roleAssignment.notFound", assignmentId));
    }

    /** Resolve assignment: use explicit ID if provided, otherwise derive from author staff. */
    private UserRoleHospitalAssignment resolveAssignment(UUID assignmentId, Staff author) {
        if (assignmentId != null) {
            return fetchAssignment(assignmentId);
        }
        return author.getAssignment();
    }

    private Staff fetchStaff(UUID staffId) {
        return staffRepository.findById(staffId)
            .orElseThrow(() -> new ResourceNotFoundException("staff.notFound", staffId));
    }

    private Staff fetchOptionalStaff(UUID staffId) {
        if (staffId == null) {
            return null;
        }
        return fetchStaff(staffId);
    }

    private Staff optionalStaff(UUID staffId) {
        return staffId == null ? null : fetchStaff(staffId);
    }

    private void applyFollowUps(TreatmentPlan plan, List<TreatmentPlanFollowUpRequestDTO> followUps) {
        plan.clearFollowUps();
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
            .toList();
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
                throw new ResourceNotFoundException("staff.notFound", id);
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
