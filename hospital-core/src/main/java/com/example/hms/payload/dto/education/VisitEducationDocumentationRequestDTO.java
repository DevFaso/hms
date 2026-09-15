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
    @NotNull(message = "{visitEducationDocumentation.encounterId.required}")
    private UUID encounterId;

    @NotNull(message = "{visitEducationDocumentation.patientId.required}")
    private UUID patientId;

    @NotNull(message = "{visitEducationDocumentation.category.required}")
    private EducationCategory category;

    @NotNull(message = "{visitEducationDocumentation.topicDiscussed.required}")
    private String topicDiscussed;
}
