package com.example.hms.payload.dto.education;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class VisitEducationDocumentationResponseDTO extends VisitEducationDocumentationBaseDTO {
    private UUID id;
    private UUID staffId;
    private UUID hospitalId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
