package com.example.hms.mapper;

import com.example.hms.model.Questionnaire;
import com.example.hms.payload.dto.portal.QuestionnaireDTO;
import org.springframework.stereotype.Component;

@Component
public class QuestionnaireMapper {

    public QuestionnaireDTO toDto(Questionnaire entity) {
        if (entity == null) return null;

        QuestionnaireDTO dto = new QuestionnaireDTO();
        dto.setId(entity.getId());
        dto.setTitle(entity.getTitle());
        dto.setDescription(entity.getDescription());
        dto.setQuestions(entity.getQuestions());
        dto.setVersion(entity.getVersion());

        if (entity.getDepartment() != null) {
            dto.setDepartmentId(entity.getDepartment().getId());
            dto.setDepartmentName(entity.getDepartment().getName());
        }

        return dto;
    }
}
