package com.example.hms.mapper;

import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.treatment.TreatmentPlan;
import com.example.hms.model.treatment.TreatmentPlanFollowUp;
import com.example.hms.model.treatment.TreatmentPlanReview;
import com.example.hms.payload.dto.clinical.treatment.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class TreatmentPlanMapper {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public TreatmentPlanResponseDTO toResponseDTO(TreatmentPlan plan) {
        if (plan == null) {
            return null;
        }

        Patient patient = plan.getPatient();
        Hospital hospital = plan.getHospital();
        Staff author = plan.getAuthor();
        Staff supervisor = plan.getSupervisingStaff();
        Staff signOff = plan.getSignOffBy();

        return TreatmentPlanResponseDTO.builder()
            .id(plan.getId())
            .patientId(patient != null ? patient.getId() : null)
            .patientName(patient != null ? patient.getFullName() : null)
            .hospitalId(hospital != null ? hospital.getId() : null)
            .hospitalName(hospital != null ? hospital.getName() : null)
            .encounterId(plan.getEncounter() != null ? plan.getEncounter().getId() : null)
            .assignmentId(plan.getAssignment() != null ? plan.getAssignment().getId() : null)
            .authorStaffId(author != null ? author.getId() : null)
            .authorStaffName(resolveStaffName(author))
            .supervisingStaffId(supervisor != null ? supervisor.getId() : null)
            .supervisingStaffName(resolveStaffName(supervisor))
            .signOffStaffId(signOff != null ? signOff.getId() : null)
            .signOffStaffName(resolveStaffName(signOff))
            .status(plan.getStatus())
            .problemStatement(plan.getProblemStatement())
            .therapeuticGoals(deserializeList(plan.getTherapeuticGoalsJson()))
            .medicationPlan(deserializeList(plan.getMedicationPlanJson()))
            .lifestylePlan(deserializeList(plan.getLifestylePlanJson()))
            .referralPlan(deserializeList(plan.getReferralPlanJson()))
            .responsibleParties(deserializeList(plan.getResponsiblePartiesJson()))
            .timelineSummary(plan.getTimelineSummary())
            .followUpSummary(plan.getFollowUpSummary())
            .timelineStartDate(plan.getTimelineStartDate())
            .timelineReviewDate(plan.getTimelineReviewDate())
            .patientVisibility(plan.getPatientVisibility())
            .patientVisibilityAt(plan.getPatientVisibilityAt())
            .signOffAt(plan.getSignOffAt())
            .version(plan.getVersion())
            .followUps(mapFollowUps(plan.getFollowUps()))
            .reviews(mapReviews(plan.getReviews()))
            .createdAt(plan.getCreatedAt())
            .updatedAt(plan.getUpdatedAt())
            .build();
    }

    public TreatmentPlan toEntity(TreatmentPlanRequestDTO request,
                                   Patient patient,
                                   TreatmentPlanContext context) {
        TreatmentPlan plan = new TreatmentPlan();
        applyRequest(plan, request, context);
        plan.setPatient(patient);
        return plan;
    }

    public void applyRequest(TreatmentPlan target,
                              TreatmentPlanRequestDTO request,
                              TreatmentPlanContext context) {
        target.setHospital(context.hospital());
        target.setEncounter(context.encounter());
        target.setAssignment(context.assignment());
        target.setAuthor(context.author());
        target.setSupervisingStaff(context.supervising());
        target.setSignOffBy(context.signOff());

        target.setProblemStatement(request.getProblemStatement());
        target.setTherapeuticGoalsJson(serializeList(request.getTherapeuticGoals()));
        target.setMedicationPlanJson(serializeList(request.getMedicationPlan()));
        target.setLifestylePlanJson(serializeList(request.getLifestylePlan()));
        target.setTimelineSummary(request.getTimelineSummary());
        target.setFollowUpSummary(request.getFollowUpSummary());
        target.setReferralPlanJson(serializeList(request.getReferralPlan()));
        target.setResponsiblePartiesJson(serializeList(request.getResponsibleParties()));
        target.setTimelineStartDate(request.getTimelineStartDate());
        target.setTimelineReviewDate(request.getTimelineReviewDate());

        if (request.getPatientVisibility() != null) {
            target.setPatientVisibility(request.getPatientVisibility());
        } else if (target.getPatientVisibility() == null) {
            target.setPatientVisibility(Boolean.FALSE);
        }
    }

    public static class TreatmentPlanContext {
        private final Hospital hospital;
        private final Encounter encounter;
        private final UserRoleHospitalAssignment assignment;
        private final Staff author;
        private final Staff supervising;
        private final Staff signOff;

        public TreatmentPlanContext(
            Hospital hospital,
            Encounter encounter,
            UserRoleHospitalAssignment assignment,
            Staff author,
            Staff supervising,
            Staff signOff
        ) {
            this.hospital = hospital;
            this.encounter = encounter;
            this.assignment = assignment;
            this.author = author;
            this.supervising = supervising;
            this.signOff = signOff;
        }

        public Hospital hospital() {
            return hospital;
        }

        public Encounter encounter() {
            return encounter;
        }

        public UserRoleHospitalAssignment assignment() {
            return assignment;
        }

        public Staff author() {
            return author;
        }

        public Staff supervising() {
            return supervising;
        }

        public Staff signOff() {
            return signOff;
        }
    }

    public List<TreatmentPlanFollowUpDTO> mapFollowUps(Set<TreatmentPlanFollowUp> followUps) {
        if (followUps == null || followUps.isEmpty()) {
            return List.of();
        }
        return followUps.stream()
            .map(this::toFollowUpDTO)
            .sorted((a, b) -> {
                if (a.getDueOn() == null && b.getDueOn() == null) return 0;
                if (a.getDueOn() == null) return 1;
                if (b.getDueOn() == null) return -1;
                return a.getDueOn().compareTo(b.getDueOn());
            })
            .toList();
    }

    public TreatmentPlanFollowUpDTO toFollowUpDTO(TreatmentPlanFollowUp followUp) {
        if (followUp == null) {
            return null;
        }

        Staff assigned = followUp.getAssignedStaff();
        return TreatmentPlanFollowUpDTO.builder()
            .id(followUp.getId())
            .label(followUp.getLabel())
            .instructions(followUp.getInstructions())
            .dueOn(followUp.getDueOn())
            .assignedStaffId(assigned != null ? assigned.getId() : null)
            .assignedStaffName(resolveStaffName(assigned))
            .status(followUp.getStatus())
            .completedAt(followUp.getCompletedAt())
            .createdAt(followUp.getCreatedAt())
            .updatedAt(followUp.getUpdatedAt())
            .build();
    }

    public List<TreatmentPlanReviewDTO> mapReviews(Set<TreatmentPlanReview> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return List.of();
        }
        return reviews.stream()
            .map(this::toReviewDTO)
            .sorted((a, b) -> {
                if (a.getCreatedAt() == null && b.getCreatedAt() == null) return 0;
                if (a.getCreatedAt() == null) return 1;
                if (b.getCreatedAt() == null) return -1;
                return a.getCreatedAt().compareTo(b.getCreatedAt());
            })
            .toList();
    }

    public TreatmentPlanReviewDTO toReviewDTO(TreatmentPlanReview review) {
        if (review == null) {
            return null;
        }
        Staff reviewer = review.getReviewer();
        return TreatmentPlanReviewDTO.builder()
            .id(review.getId())
            .reviewerStaffId(reviewer != null ? reviewer.getId() : null)
            .reviewerName(resolveStaffName(reviewer))
            .action(review.getAction())
            .comment(review.getComment())
            .createdAt(review.getCreatedAt())
            .build();
    }

    public void replaceFollowUps(TreatmentPlan plan, List<TreatmentPlanFollowUp> followUps) {
        plan.getFollowUps().clear();
        if (followUps != null) {
            followUps.forEach(plan::addFollowUp);
        }
    }

    private List<String> deserializeList(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize treatment plan payload: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String serializeList(List<String> values) {
        if (CollectionUtils.isEmpty(values)) {
            return null;
        }
        List<String> sanitized = values.stream()
            .filter(StringUtils::hasText)
            .map(v -> v.trim())
            .filter(v -> !v.isEmpty())
            .toList();
        if (sanitized.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(sanitized);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize treatment plan payload", e);
        }
    }

    private String resolveStaffName(Staff staff) {
        if (staff == null) {
            return null;
        }
        if (StringUtils.hasText(staff.getName())) {
            return staff.getName();
        }
        return staff.getFullName();
    }
}
