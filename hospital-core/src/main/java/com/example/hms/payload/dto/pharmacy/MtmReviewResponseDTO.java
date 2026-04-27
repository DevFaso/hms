package com.example.hms.payload.dto.pharmacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MtmReviewResponseDTO {

    private UUID id;
    private UUID patientId;
    private UUID hospitalId;
    private UUID pharmacistUserId;
    private LocalDateTime reviewDate;
    private String chronicConditionFocus;
    private boolean adherenceConcern;
    private boolean polypharmacyAlert;
    private String interventionSummary;
    private String recommendedActions;
    private String status;
    private LocalDate followUpDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
