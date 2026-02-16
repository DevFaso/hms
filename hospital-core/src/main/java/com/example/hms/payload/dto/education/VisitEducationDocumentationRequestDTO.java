package com.example.hms.payload.dto.education;

import com.example.hms.enums.EducationCategory;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class VisitEducationDocumentationRequestDTO extends VisitEducationDocumentationBaseDTO {
    @NotNull(message = "Encounter ID is required")
    private UUID encounterId;

    @NotNull(message = "Patient ID is required")
    private UUID patientId;

    @NotNull(message = "Category is required")
    private EducationCategory category;

    @NotNull(message = "Topic discussed is required")
    private String topicDiscussed;
}
