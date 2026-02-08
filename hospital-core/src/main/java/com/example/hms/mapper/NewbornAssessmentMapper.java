package com.example.hms.mapper;

import com.example.hms.enums.NewbornEducationTopic;
import com.example.hms.enums.NewbornFollowUpAction;
import com.example.hms.model.neonatal.NewbornAssessment;
import com.example.hms.model.neonatal.NewbornAssessmentAlert;
import com.example.hms.payload.dto.clinical.newborn.NewbornAssessmentAlertDTO;
import com.example.hms.payload.dto.clinical.newborn.NewbornAssessmentResponseDTO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class NewbornAssessmentMapper {

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
                .message(alert.getMessage())
                .triggeredBy(alert.getTriggeredBy())
                .createdAt(alert.getCreatedAt())
                .build())
            .toList();
    }
}
