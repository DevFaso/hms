package com.example.hms.mapper;

import com.example.hms.model.education.PatientEducationQuestion;
import com.example.hms.payload.dto.education.PatientEducationQuestionRequestDTO;
import com.example.hms.payload.dto.education.PatientEducationQuestionResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class PatientEducationQuestionMapper {
    
    public PatientEducationQuestionResponseDTO toResponseDTO(PatientEducationQuestion entity) {
        if (entity == null) return null;
        
        return PatientEducationQuestionResponseDTO.builder()
            .id(entity.getId())
            .patientId(entity.getPatientId())
            .resourceId(entity.getResourceId())
            .hospitalId(entity.getHospitalId())
            .questionText(entity.getQuestion())
            .isUrgent(entity.getIsUrgent())
            .isAnswered(entity.getIsAnswered())
            .answerText(entity.getAnswer())
            .answeredByStaffId(entity.getAnsweredByStaffId())
            .answeredAt(entity.getAnsweredAt())
            .requiresInPersonDiscussion(entity.getRequiresInPersonDiscussion())
            .appointmentScheduled(entity.getAppointmentScheduled())
            .providerNotes(entity.getProviderNotes())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }

    public PatientEducationQuestion toEntity(PatientEducationQuestionRequestDTO dto) {
        if (dto == null) return null;

        return PatientEducationQuestion.builder()
            .resourceId(dto.getResourceId())
            .question(dto.getQuestionText())
            .isUrgent(dto.getIsUrgent())
            .requiresInPersonDiscussion(dto.getRequiresInPersonDiscussion())
            .answer(dto.getAnswerText())
            .isAnswered(dto.getIsAnswered())
            .appointmentScheduled(dto.getAppointmentScheduled())
            .build();
    }
    
    public void updateEntityFromDTO(PatientEducationQuestionRequestDTO dto, PatientEducationQuestion entity) {
        if (dto == null || entity == null) return;
        
        if (dto.getQuestionText() != null) entity.setQuestion(dto.getQuestionText());
        if (dto.getIsUrgent() != null) entity.setIsUrgent(dto.getIsUrgent());
        if (dto.getAnswerText() != null) entity.setAnswer(dto.getAnswerText());
        if (dto.getIsAnswered() != null) entity.setIsAnswered(dto.getIsAnswered());
        if (dto.getRequiresInPersonDiscussion() != null) entity.setRequiresInPersonDiscussion(dto.getRequiresInPersonDiscussion());
        if (dto.getAppointmentScheduled() != null) entity.setAppointmentScheduled(dto.getAppointmentScheduled());
    }
}
