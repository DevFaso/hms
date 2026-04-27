package com.example.hms.payload.dto.pharmacy;

import com.example.hms.enums.MtmReviewStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MtmReviewRequestDTO {

    @NotNull(message = "Patient ID is required")
    private UUID patientId;

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;

    @Size(max = 500)
    private String chronicConditionFocus;

    private Boolean adherenceConcern;

    @Size(max = 2000)
    private String interventionSummary;

    @Size(max = 2000)
    private String recommendedActions;

    /** Optional on create (defaults to DRAFT); required on update to drive lifecycle. */
    private MtmReviewStatus status;

    private LocalDate followUpDate;
}
