package com.example.hms.mapper;

import com.example.hms.enums.NewbornEducationTopic;
import com.example.hms.enums.NewbornFollowUpAction;
import com.example.hms.model.neonatal.NewbornAssessment;
import com.example.hms.model.neonatal.NewbornAssessmentAlert;
import com.example.hms.payload.dto.clinical.newborn.NewbornAssessmentAlertDTO;
import com.example.hms.payload.dto.clinical.newborn.NewbornAssessmentResponseDTO;
import com.example.hms.service.i18n.NotificationLocalizer;
import com.example.hms.service.maternity.ObstetricAlertText;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class NewbornAssessmentMapper {

    private static final String ALERT_MSG_PREFIX = "newborn.alert.msg.";
    private static final String ALERT_TRIGGER_PREFIX = "newborn.alert.trigger.";

    private final NotificationLocalizer localizer;

    /** Without a localizer every alert shows its stored text. */
    public NewbornAssessmentMapper() {
        this(null);
    }

    @Autowired
    public NewbornAssessmentMapper(NotificationLocalizer localizer) {
        this.localizer = localizer;
    }

    public NewbornAssessmentResponseDTO toResponse(NewbornAssessment assessment) {
        if (assessment == null) {
            return null;
        }
        Set<NewbornFollowUpAction> followUpActions = assessment.getFollowUpActions() == null
            ? Set.of()
            : Set.copyOf(assessment.getFollowUpActions());
        Set<NewbornEducationTopic> educationTopics = assessment.getParentEducationTopics() == null
            ? Set.of()
            : Set.copyOf(assessment.getParentEducationTopics());
        List<NewbornAssessmentAlertDTO> alerts = mapAlerts(assessment.getAlerts());

        return NewbornAssessmentResponseDTO.builder()
            .id(assessment.getId())
            .patientId(assessment.getPatient() != null ? assessment.getPatient().getId() : null)
            .hospitalId(assessment.getHospital() != null ? assessment.getHospital().getId() : null)
            .registrationId(assessment.getRegistration() != null ? assessment.getRegistration().getId() : null)
            .deliveryRecordId(assessment.getDeliveryRecord() != null
                ? assessment.getDeliveryRecord().getId() : null)
            .assessmentTime(assessment.getAssessmentTime())
            .documentedAt(assessment.getDocumentedAt())
            .lateEntry(assessment.isLateEntry())
            .originalEntryTime(assessment.getOriginalEntryTime())
            .apgarOneMinute(assessment.getApgarOneMinute())
            .apgarFiveMinute(assessment.getApgarFiveMinute())
            .apgarTenMinute(assessment.getApgarTenMinute())
            .apgarNotes(assessment.getApgarNotes())
            .temperatureCelsius(assessment.getTemperatureCelsius())
            .heartRateBpm(assessment.getHeartRateBpm())
            .respirationsPerMin(assessment.getRespirationsPerMin())
            .systolicBpMmHg(assessment.getSystolicBpMmHg())
            .diastolicBpMmHg(assessment.getDiastolicBpMmHg())
            .oxygenSaturationPercent(assessment.getOxygenSaturationPercent())
            .glucoseMgDl(assessment.getGlucoseMgDl())
            .examGeneralAppearance(assessment.getExamGeneralAppearance())
            .examHeadNeck(assessment.getExamHeadNeck())
            .examChestLungs(assessment.getExamChestLungs())
            .examCardiac(assessment.getExamCardiac())
            .examAbdomen(assessment.getExamAbdomen())
            .examGenitourinary(assessment.getExamGenitourinary())
            .examSkin(assessment.getExamSkin())
            .examNeurological(assessment.getExamNeurological())
            .examMusculoskeletal(assessment.getExamMusculoskeletal())
            .examNotes(assessment.getExamNotes())
            .poorFeeding(assessment.getPoorFeeding())
            .convulsions(assessment.getConvulsions())
            .severeChestIndrawing(assessment.getSevereChestIndrawing())
            .absentSpontaneousMovement(assessment.getAbsentSpontaneousMovement())
            .escalationRecommended(assessment.isEscalationRecommended())
            .respiratorySupportInitiated(assessment.isRespiratorySupportInitiated())
            .glucoseProtocolInitiated(assessment.isGlucoseProtocolInitiated())
            .thermoregulationSupportInitiated(assessment.isThermoregulationSupportInitiated())
            .followUpNotes(assessment.getFollowUpNotes())
            .followUpActions(followUpActions)
            .parentEducationTopics(educationTopics)
            .parentEducationNotes(assessment.getParentEducationNotes())
            .parentEducationCompleted(assessment.isParentEducationCompleted())
            .alerts(alerts)
            .build();
    }

    private List<NewbornAssessmentAlertDTO> mapAlerts(List<NewbornAssessmentAlert> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            return List.of();
        }
        return alerts.stream()
            .map(alert -> NewbornAssessmentAlertDTO.builder()
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
}
