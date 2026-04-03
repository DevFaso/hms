package com.example.hms.payload.dto;

import com.example.hms.enums.ValidationStudyType;
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
public class LabTestValidationStudyRequestDTO {

    @NotNull
    private ValidationStudyType studyType;

    @NotNull
    private LocalDate studyDate;

    private UUID performedByUserId;

    @Size(max = 255)
    private String performedByDisplay;

    @Size(max = 2048)
    private String summary;

    /** Optional JSON blob with CLSI protocol metrics (SD, CV%, bias%, linearity). */
    private String resultData;

    @NotNull
    private Boolean passed;

    @Size(max = 2048)
    private String notes;
}
