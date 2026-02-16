package com.example.hms.mapper;

import com.example.hms.enums.PostpartumEducationTopic;
import com.example.hms.enums.PostpartumSchedulePhase;
import com.example.hms.model.postpartum.PostpartumCarePlan;
import com.example.hms.model.postpartum.PostpartumObservation;
import com.example.hms.model.postpartum.PostpartumObservationAlert;
import com.example.hms.payload.dto.clinical.postpartum.PostpartumAlertDTO;
import com.example.hms.payload.dto.clinical.postpartum.PostpartumObservationResponseDTO;
import com.example.hms.payload.dto.clinical.postpartum.PostpartumScheduleDTO;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Component
public class PostpartumObservationMapper {

    public PostpartumObservationResponseDTO toResponse(PostpartumObservation observation, PostpartumCarePlan plan) {
        if (observation == null) {
            return null;
        }
        PostpartumCarePlan effectivePlan = plan != null ? plan : observation.getCarePlan();
        PostpartumScheduleDTO schedule = buildScheduleSnapshot(effectivePlan);
        List<PostpartumAlertDTO> alerts = mapAlerts(observation.getAlerts());
        Set<PostpartumEducationTopic> educationTopics = observation.getEducationTopics() == null
            ? Set.of()
            : Set.copyOf(observation.getEducationTopics());

        return PostpartumObservationResponseDTO.builder()
            .id(observation.getId())
            .patientId(observation.getPatient() != null ? observation.getPatient().getId() : null)
            .hospitalId(observation.getHospital() != null ? observation.getHospital().getId() : null)
            .carePlanId(effectivePlan != null ? effectivePlan.getId() : null)
            .observationTime(observation.getObservationTime())
            .documentedAt(observation.getDocumentedAt())
            .lateEntry(observation.isLateEntry())
            .originalEntryTime(observation.getOriginalEntryTime())
            .temperatureCelsius(observation.getTemperatureCelsius())
            .systolicBpMmHg(observation.getSystolicBpMmHg())
            .diastolicBpMmHg(observation.getDiastolicBpMmHg())
            .pulseBpm(observation.getPulseBpm())
            .respirationsPerMin(observation.getRespirationsPerMin())
            .painScore(observation.getPainScore())
            .fundusHeightCm(observation.getFundusHeightCm())
            .fundusTone(observation.getFundusTone())
            .bladderStatus(observation.getBladderStatus())
            .lochiaAmount(observation.getLochiaAmount())
            .lochiaCharacter(observation.getLochiaCharacter())
            .lochiaNotes(observation.getLochiaNotes())
            .perineumFindings(observation.getPerineumFindings())
            .uterineAtonySuspected(observation.isUterineAtonySuspected())
            .excessiveBleeding(observation.isExcessiveBleeding())
            .estimatedBloodLossMl(observation.getEstimatedBloodLossMl())
            .uterotonicGiven(observation.isUterotonicGiven())
            .hemorrhageProtocolActivated(observation.isHemorrhageProtocolActivated())
            .foulLochiaOdor(observation.isFoulLochiaOdor())
            .uterineTenderness(observation.isUterineTenderness())
            .chillsOrRigors(observation.isChillsOrRigors())
            .moodStatus(observation.getMoodStatus())
            .supportStatus(observation.getSupportStatus())
            .sleepStatus(observation.getSleepStatus())
            .psychosocialNotes(observation.getPsychosocialNotes())
            .mentalHealthReferralSuggested(observation.isMentalHealthReferralSuggested())
            .socialSupportReferralSuggested(observation.isSocialSupportReferralSuggested())
            .painManagementReferralSuggested(observation.isPainManagementReferralSuggested())
            .educationTopics(educationTopics)
            .educationNotes(observation.getEducationNotes())
            .educationCompleted(observation.isEducationCompleted())
            .postpartumVisitDate(observation.getPostpartumVisitDate())
            .dischargeChecklistComplete(observation.isDischargeChecklistComplete())
            .rhImmunoglobulinCompleted(observation.isRhImmunoglobulinCompleted())
            .immunizationsUpdated(observation.isImmunizationsUpdated())
            .hemorrhageProtocolConfirmed(observation.isHemorrhageProtocolConfirmed())
            .uterotonicAvailabilityConfirmed(observation.isUterotonicAvailabilityConfirmed())
            .contactInfoVerified(observation.isContactInfoVerified())
            .followUpContactMethod(observation.getFollowUpContactMethod())
            .dischargeSafetyNotes(observation.getDischargeSafetyNotes())
            .schedulePhaseAtEntry(observation.getSchedulePhaseAtEntry())
            .nextDueAtSnapshot(observation.getNextDueAtSnapshot())
            .overdueSinceSnapshot(observation.getOverdueSinceSnapshot())
            .supersedesObservationId(observation.getSupersedesObservation() != null
                ? observation.getSupersedesObservation().getId() : null)
            .signoffName(observation.getSignoffName())
            .signoffCredentials(observation.getSignoffCredentials())
            .signedAt(observation.getSignedAt())
            .schedule(schedule)
            .alerts(alerts)
            .build();
    }

    private List<PostpartumAlertDTO> mapAlerts(List<PostpartumObservationAlert> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            return List.of();
        }
        return alerts.stream()
            .map(alert -> PostpartumAlertDTO.builder()
                .type(alert.getType())
                .severity(alert.getSeverity())
                .code(alert.getCode())
                .message(alert.getMessage())
                .triggeredBy(alert.getTriggeredBy())
                .createdAt(alert.getCreatedAt())
                .build())
            .toList();
    }

    private PostpartumScheduleDTO buildScheduleSnapshot(PostpartumCarePlan plan) {
        if (plan == null) {
            return null;
        }
        boolean overdue = plan.getOverdueSince() != null && plan.getOverdueSince().isBefore(LocalDateTime.now());
        Integer frequency = plan.getActivePhase() == PostpartumSchedulePhase.DISCHARGE_PLANNING
            ? null
            : plan.getShiftFrequencyMinutes();
        return PostpartumScheduleDTO.builder()
            .carePlanId(plan.getId())
            .phase(plan.getActivePhase())
            .immediateWindowComplete(plan.isImmediateWindowCompleted())
            .immediateChecksCompleted(plan.getImmediateObservationsCompleted())
            .immediateCheckTarget(plan.getImmediateObservationTarget())
            .frequencyMinutes(frequency)
            .nextDueAt(plan.getNextDueAt())
            .overdueSince(plan.getOverdueSince())
            .overdue(overdue)
            .build();
    }
}
