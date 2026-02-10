package com.example.hms.mapper;

import com.example.hms.enums.HighRiskMilestoneType;
import com.example.hms.model.Patient;
import com.example.hms.model.Hospital;
import com.example.hms.model.highrisk.HighRiskBloodPressureLog;
import com.example.hms.model.highrisk.HighRiskCareTeamMember;
import com.example.hms.model.highrisk.HighRiskCareTeamNote;
import com.example.hms.model.highrisk.HighRiskEducationTopic;
import com.example.hms.model.highrisk.HighRiskMedicationLog;
import com.example.hms.model.highrisk.HighRiskMonitoringMilestone;
import com.example.hms.model.highrisk.HighRiskPregnancyCarePlan;
import com.example.hms.model.highrisk.HighRiskSupportResource;
import com.example.hms.payload.dto.highrisk.HighRiskBloodPressureLogRequestDTO;
import com.example.hms.payload.dto.highrisk.HighRiskCareTeamNoteRequestDTO;
import com.example.hms.payload.dto.highrisk.HighRiskMedicationLogRequestDTO;
import com.example.hms.payload.dto.highrisk.HighRiskPregnancyCarePlanRequestDTO;
import com.example.hms.payload.dto.highrisk.HighRiskPregnancyCarePlanResponseDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mapper between high-risk pregnancy plan entities and DTOs.
 */
@Component
public class HighRiskPregnancyCarePlanMapper {

    public void updateEntityFromRequest(
        HighRiskPregnancyCarePlan plan,
        HighRiskPregnancyCarePlanRequestDTO request,
        boolean replaceMissingWithEmpty
    ) {
        if (request == null) {
            return;
        }

        updateRiskProfile(plan, request.getRiskProfile(), replaceMissingWithEmpty);
        updateMonitoringPlan(plan, request.getMonitoringPlan(), replaceMissingWithEmpty);
        updateEducationPlan(plan, request.getEducationPlan(), replaceMissingWithEmpty);
        updateCareCoordination(plan, request.getCareCoordination(), replaceMissingWithEmpty);
        updateSupportPlan(plan, request.getSupportPlan(), replaceMissingWithEmpty);

        if (request.getOverallNotes() != null || replaceMissingWithEmpty) {
            plan.setOverallNotes(request.getOverallNotes());
        }

        if (request.getActive() != null) {
            plan.setActive(request.getActive());
        } else if (replaceMissingWithEmpty && plan.getActive() == null) {
            plan.setActive(Boolean.TRUE);
        }
    }

    private void updateRiskProfile(
        HighRiskPregnancyCarePlan plan,
        HighRiskPregnancyCarePlanRequestDTO.RiskProfileDTO profile,
        boolean replaceMissingWithEmpty
    ) {
        if (profile != null) {
            plan.setRiskLevel(profile.getRiskLevel());
            plan.setRiskNotes(profile.getRiskNotes());
            updateList(profile.getPreexistingConditions(), replaceMissingWithEmpty, plan::setPreexistingConditions);
            updateList(profile.getPregnancyConditions(), replaceMissingWithEmpty, plan::setPregnancyConditions);
            updateList(profile.getLifestyleFactors(), replaceMissingWithEmpty, plan::setLifestyleFactors);
            return;
        }

        if (replaceMissingWithEmpty) {
            plan.setPreexistingConditions(new ArrayList<>());
            plan.setPregnancyConditions(new ArrayList<>());
            plan.setLifestyleFactors(new ArrayList<>());
        }
    }

    private void updateMonitoringPlan(
        HighRiskPregnancyCarePlan plan,
        HighRiskPregnancyCarePlanRequestDTO.MonitoringPlanDTO monitoring,
        boolean replaceMissingWithEmpty
    ) {
        if (monitoring != null) {
            plan.setVisitCadence(monitoring.getVisitCadence());
            plan.setHomeMonitoringInstructions(monitoring.getHomeMonitoringInstructions());
            plan.setMedicationPlan(monitoring.getMedicationPlan());
            plan.setLastSpecialistReview(monitoring.getLastSpecialistReview());
            updateList(monitoring.getMilestones(), replaceMissingWithEmpty, this::toEntityMilestone, plan::setMonitoringMilestones);
            return;
        }

        if (replaceMissingWithEmpty) {
            plan.setVisitCadence(null);
            plan.setHomeMonitoringInstructions(null);
            plan.setMedicationPlan(null);
            plan.setMonitoringMilestones(new ArrayList<>());
        }
    }

    private void updateEducationPlan(
        HighRiskPregnancyCarePlan plan,
        HighRiskPregnancyCarePlanRequestDTO.EducationPlanDTO education,
        boolean replaceMissingWithEmpty
    ) {
        if (education != null) {
            updateList(education.getPreventiveGuidance(), replaceMissingWithEmpty, plan::setPreventiveGuidance);
            updateList(education.getEmergencySymptoms(), replaceMissingWithEmpty, plan::setEmergencySymptoms);
            updateList(education.getTopics(), replaceMissingWithEmpty, topic -> HighRiskEducationTopic.builder()
                .topic(topic.getTopic())
                .guidance(topic.getGuidance())
                .materials(topic.getMaterials())
                .build(), plan::setEducationTopics);
            return;
        }

        if (replaceMissingWithEmpty) {
            plan.setPreventiveGuidance(new ArrayList<>());
            plan.setEmergencySymptoms(new ArrayList<>());
            plan.setEducationTopics(new ArrayList<>());
        }
    }

    private void updateCareCoordination(
        HighRiskPregnancyCarePlan plan,
        HighRiskPregnancyCarePlanRequestDTO.CareCoordinationDTO coordination,
        boolean replaceMissingWithEmpty
    ) {
        if (coordination != null) {
            plan.setCoordinationNotes(coordination.getCoordinationNotes());
            plan.setDeliveryRecommendations(coordination.getDeliveryRecommendations());
            plan.setEscalationPlan(coordination.getEscalationPlan());
            updateList(coordination.getCareTeam(), replaceMissingWithEmpty, member -> HighRiskCareTeamMember.builder()
                .name(member.getName())
                .role(member.getRole())
                .contact(member.getContact())
                .notes(member.getNotes())
                .build(), plan::setCareTeamMembers);
            updateList(coordination.getCommunications(), replaceMissingWithEmpty, this::toEntityCareTeamNote, plan::setCareTeamNotes);
            return;
        }

        if (replaceMissingWithEmpty) {
            plan.setCoordinationNotes(null);
            plan.setDeliveryRecommendations(null);
            plan.setEscalationPlan(null);
            plan.setCareTeamMembers(new ArrayList<>());
            plan.setCareTeamNotes(new ArrayList<>());
        }
    }

    private HighRiskCareTeamNote toEntityCareTeamNote(HighRiskPregnancyCarePlanRequestDTO.CareTeamNoteDTO note) {
        if (note == null) {
            return null;
        }
        UUID noteId = note.getNoteId() != null ? note.getNoteId() : UUID.randomUUID();
        return HighRiskCareTeamNote.builder()
            .noteId(noteId)
            .loggedAt(note.getLoggedAt())
            .author(note.getAuthor())
            .summary(note.getSummary())
            .followUp(note.getFollowUp())
            .build();
    }

    private void updateSupportPlan(
        HighRiskPregnancyCarePlan plan,
        HighRiskPregnancyCarePlanRequestDTO.SupportPlanDTO support,
        boolean replaceMissingWithEmpty
    ) {
        if (support != null) {
            updateList(support.getResources(), replaceMissingWithEmpty, resource -> HighRiskSupportResource.builder()
                .name(resource.getName())
                .type(resource.getType())
                .contact(resource.getContact())
                .url(resource.getUrl())
                .notes(resource.getNotes())
                .build(), plan::setSupportResources);
            return;
        }

        if (replaceMissingWithEmpty) {
            plan.setSupportResources(new ArrayList<>());
        }
    }

    public HighRiskPregnancyCarePlanResponseDTO toResponse(HighRiskPregnancyCarePlan plan, List<String> alerts) {
        if (plan == null) {
            return null;
        }

        Patient patient = plan.getPatient();
        Hospital hospital = plan.getHospital();
        String patientMrn = resolvePatientMrn(patient, hospital);
        String patientDisplayName = resolvePatientDisplayName(patient);

        return HighRiskPregnancyCarePlanResponseDTO.builder()
            .id(plan.getId())
            .patientId(plan.getPatient() != null ? plan.getPatient().getId() : null)
            .hospitalId(plan.getHospital() != null ? plan.getHospital().getId() : null)
            .patientDisplayName(patientDisplayName)
            .patientMrn(patientMrn)
            .patientPrimaryPhone(patient != null ? patient.getPhoneNumberPrimary() : null)
            .patientEmail(patient != null ? patient.getEmail() : null)
            .patientDateOfBirth(patient != null ? patient.getDateOfBirth() : null)
            .riskLevel(plan.getRiskLevel())
            .riskNotes(plan.getRiskNotes())
            .active(Boolean.TRUE.equals(plan.getActive()))
            .lastSpecialistReview(plan.getLastSpecialistReview())
            .visitCadence(plan.getVisitCadence())
            .homeMonitoringInstructions(plan.getHomeMonitoringInstructions())
            .medicationPlan(plan.getMedicationPlan())
            .coordinationNotes(plan.getCoordinationNotes())
            .deliveryRecommendations(plan.getDeliveryRecommendations())
            .escalationPlan(plan.getEscalationPlan())
            .overallNotes(plan.getOverallNotes())
            .createdAt(plan.getCreatedAt())
            .updatedAt(plan.getUpdatedAt())
            .preexistingConditions(copyOf(plan.getPreexistingConditions()))
            .pregnancyConditions(copyOf(plan.getPregnancyConditions()))
            .lifestyleFactors(copyOf(plan.getLifestyleFactors()))
            .preventiveGuidance(copyOf(plan.getPreventiveGuidance()))
            .emergencySymptoms(copyOf(plan.getEmergencySymptoms()))
            .educationTopics(plan.getEducationTopics().stream()
                .map(topic -> HighRiskPregnancyCarePlanResponseDTO.EducationTopicDTO.builder()
                    .topic(topic.getTopic())
                    .guidance(topic.getGuidance())
                    .materials(topic.getMaterials())
                    .build())
                .collect(Collectors.toCollection(ArrayList::new)))
            .careTeam(plan.getCareTeamMembers().stream()
                .map(member -> HighRiskPregnancyCarePlanResponseDTO.CareTeamMemberDTO.builder()
                    .name(member.getName())
                    .role(member.getRole())
                    .contact(member.getContact())
                    .notes(member.getNotes())
                    .build())
                .collect(Collectors.toCollection(ArrayList::new)))
            .supportResources(plan.getSupportResources().stream()
                .map(resource -> HighRiskPregnancyCarePlanResponseDTO.SupportResourceDTO.builder()
                    .name(resource.getName())
                    .type(resource.getType())
                    .contact(resource.getContact())
                    .url(resource.getUrl())
                    .notes(resource.getNotes())
                    .build())
                .collect(Collectors.toCollection(ArrayList::new)))
            .communications(plan.getCareTeamNotes().stream()
                .map(note -> HighRiskPregnancyCarePlanResponseDTO.CareTeamNoteDTO.builder()
                    .noteId(note.getNoteId())
                    .loggedAt(note.getLoggedAt())
                    .author(note.getAuthor())
                    .summary(note.getSummary())
                    .followUp(note.getFollowUp())
                    .build())
                .collect(Collectors.toCollection(ArrayList::new)))
            .milestones(plan.getMonitoringMilestones().stream()
                .map(milestone -> HighRiskPregnancyCarePlanResponseDTO.MilestoneDTO.builder()
                    .milestoneId(milestone.getMilestoneId())
                    .type(milestone.getType())
                    .targetDate(milestone.getTargetDate())
                    .completed(milestone.getCompleted())
                    .completedAt(milestone.getCompletedAt())
                    .assignedTo(milestone.getAssignedTo())
                    .location(milestone.getLocation())
                    .summary(milestone.getSummary())
                    .followUpActions(milestone.getFollowUpActions())
                    .build())
                .collect(Collectors.toCollection(ArrayList::new)))
            .bloodPressureLogs(plan.getBloodPressureLogs().stream()
                .map(log -> HighRiskPregnancyCarePlanResponseDTO.BloodPressureLogDTO.builder()
                    .logId(log.getLogId())
                    .readingDate(log.getReadingDate())
                    .systolic(log.getSystolic())
                    .diastolic(log.getDiastolic())
                    .heartRate(log.getHeartRate())
                    .notes(log.getNotes())
                    .build())
                .collect(Collectors.toCollection(ArrayList::new)))
            .medicationLogs(plan.getMedicationLogs().stream()
                .map(log -> HighRiskPregnancyCarePlanResponseDTO.MedicationLogDTO.builder()
                    .logId(log.getLogId())
                    .medicationName(log.getMedicationName())
                    .dosage(log.getDosage())
                    .taken(log.getTaken())
                    .takenAt(log.getTakenAt())
                    .notes(log.getNotes())
                    .build())
                .collect(Collectors.toCollection(ArrayList::new)))
            .alerts(alerts != null ? new ArrayList<>(alerts) : new ArrayList<>())
            .build();
    }

    private String resolvePatientMrn(Patient patient, Hospital hospital) {
        if (patient == null || hospital == null || hospital.getId() == null) {
            return null;
        }
        return Optional.ofNullable(patient.getMrnForHospital(hospital.getId()))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .orElse(null);
    }

    private String resolvePatientDisplayName(Patient patient) {
        if (patient == null) {
            return null;
        }
        String first = safeTrim(patient.getFirstName());
        String last = safeTrim(patient.getLastName());
        String fullName = Stream.of(first, last)
            .filter(part -> part != null && !part.isEmpty())
            .collect(Collectors.joining(" "));
        if (!fullName.isEmpty()) {
            return fullName;
        }
        String email = safeTrim(patient.getEmail());
        if (email != null) {
            return email;
        }
        return patient.getId() != null ? patient.getId().toString() : null;
    }

    private String safeTrim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private HighRiskMonitoringMilestone toEntityMilestone(HighRiskPregnancyCarePlanRequestDTO.MilestoneDTO dto) {
        if (dto == null) {
            return null;
        }
        HighRiskMilestoneType type = dto.getType();
        return HighRiskMonitoringMilestone.builder()
            .milestoneId(dto.getMilestoneId() != null ? dto.getMilestoneId() : UUID.randomUUID())
            .type(type)
            .targetDate(dto.getTargetDate())
            .completed(dto.getCompleted() != null ? dto.getCompleted() : Boolean.FALSE)
            .completedAt(dto.getCompletedAt())
            .assignedTo(dto.getAssignedTo())
            .location(dto.getLocation())
            .summary(dto.getSummary())
            .followUpActions(dto.getFollowUpActions())
            .build();
    }

    private List<String> copyOf(List<String> source) {
        return source == null ? new ArrayList<>() : new ArrayList<>(source);
    }

    private <T> void updateList(List<T> source, boolean replaceMissingWithEmpty, Consumer<List<T>> setter) {
        if (source != null) {
            setter.accept(new ArrayList<>(source));
        } else if (replaceMissingWithEmpty) {
            setter.accept(new ArrayList<>());
        }
    }

    private <S, T> void updateList(
        List<S> source,
        boolean replaceMissingWithEmpty,
        Function<S, T> mapper,
        Consumer<List<T>> setter
    ) {
        if (source != null) {
            setter.accept(source.stream()
                .map(mapper)
                .collect(Collectors.toCollection(ArrayList::new)));
        } else if (replaceMissingWithEmpty) {
            setter.accept(new ArrayList<>());
        }
    }

    public HighRiskCareTeamNote toEntityNote(HighRiskCareTeamNote note, HighRiskPregnancyCarePlanRequestDTO.CareTeamNoteDTO dto) {
        if (dto == null) {
            return note;
        }
        HighRiskCareTeamNote target = note != null ? note : new HighRiskCareTeamNote();
        target.setNoteId(dto.getNoteId() != null ? dto.getNoteId() : UUID.randomUUID());
        target.setLoggedAt(dto.getLoggedAt());
        target.setAuthor(dto.getAuthor());
        target.setSummary(dto.getSummary());
        target.setFollowUp(dto.getFollowUp());
        return target;
    }

    public HighRiskCareTeamNote toEntityNote(HighRiskCareTeamNoteRequestDTO dto) {
        return HighRiskCareTeamNote.builder()
            .noteId(UUID.randomUUID())
            .loggedAt(dto.getLoggedAt())
            .author(dto.getAuthor())
            .summary(dto.getSummary())
            .followUp(dto.getFollowUp())
            .build();
    }

    public HighRiskBloodPressureLog toEntityBloodPressureLog(HighRiskBloodPressureLogRequestDTO request) {
        return HighRiskBloodPressureLog.builder()
            .logId(UUID.randomUUID())
            .readingDate(request.getReadingDate())
            .systolic(request.getSystolic())
            .diastolic(request.getDiastolic())
            .heartRate(request.getHeartRate())
            .notes(request.getNotes())
            .build();
    }

    public HighRiskMedicationLog toEntityMedicationLog(HighRiskMedicationLogRequestDTO request) {
        return HighRiskMedicationLog.builder()
            .logId(UUID.randomUUID())
            .medicationName(request.getMedicationName())
            .dosage(request.getDosage())
            .taken(request.getTaken())
            .takenAt(request.getTakenAt())
            .notes(request.getNotes())
            .build();
    }

    public List<HighRiskMonitoringMilestone> ensureMilestonesContainTypes(
        List<HighRiskMonitoringMilestone> existing,
        HighRiskMilestoneType... requiredTypes
    ) {
        List<HighRiskMonitoringMilestone> result = new ArrayList<>(existing != null ? existing : List.of());
        Stream.of(requiredTypes)
            .filter(type -> result.stream().noneMatch(m -> m.getType() == type))
            .forEach(type -> result.add(HighRiskMonitoringMilestone.builder()
                .milestoneId(UUID.randomUUID())
                .type(type)
                .completed(Boolean.FALSE)
                .build()));
        return result;
    }
}
