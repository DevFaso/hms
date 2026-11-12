package com.example.hms.mapper;

import com.example.hms.enums.PostpartumEducationTopic;
import com.example.hms.model.postpartum.PostpartumCarePlan;
import com.example.hms.model.postpartum.PostpartumObservation;
import com.example.hms.model.postpartum.PostpartumObservationAlert;
import com.example.hms.payload.dto.clinical.postpartum.PostpartumAlertDTO;
import com.example.hms.payload.dto.clinical.postpartum.PostpartumObservationResponseDTO;
import com.example.hms.payload.dto.clinical.postpartum.PostpartumScheduleDTO;
import com.example.hms.service.i18n.NotificationLocalizer;
import com.example.hms.service.maternity.ObstetricAlertText;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Component
public class PostpartumObservationMapper {

    private static final String ALERT_MSG_PREFIX = "postpartum.alert.msg.";
    private static final String ALERT_TRIGGER_PREFIX = "postpartum.alert.trigger.";

    private final NotificationLocalizer localizer;

    /** Without a localizer every alert shows its stored text. */
    public PostpartumObservationMapper() {
        this(null);
    }

    @Autowired
    public PostpartumObservationMapper(NotificationLocalizer localizer) {
        this.localizer = localizer;
    }

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
            .hospitalName(observation.getHospital() != null ? observation.getHospital().getName() : null)
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
                // QA D1427: the stored copy is in the staff language; the reader reads hers.
                .message(ObstetricAlertText.render(localizer, ALERT_MSG_PREFIX, alert.getCode(), List.of(), alert.getMessage()))
                .triggeredBy(ObstetricAlertText.render(localizer, ALERT_TRIGGER_PREFIX, alert.getCode(), List.of(), alert.getTriggeredBy()))
                .createdAt(alert.getCreatedAt())
                .build())
            .toList();
    }

    private PostpartumScheduleDTO buildScheduleSnapshot(PostpartumCarePlan plan) {
        if (plan == null) {
            return null;
        }
        // QA D215: overdue means the next check is in the past, not a stale overdueSince.
        boolean overdue = plan.getNextDueAt() != null && plan.getNextDueAt().isBefore(LocalDateTime.now());
        Integer frequency = plan.currentCheckFrequencyMinutes();
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
