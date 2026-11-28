package com.example.hms.payload.dto.encounter.workspace;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EncounterWorkspaceAddendumRequestDTO {

    @NotBlank(message = "{addendum.validation.contentLength}")
    @Size(min = 10, max = 4000, message = "{addendum.validation.contentLength}")
    private String content;

    @NotNull(message = "{addendum.validation.eventTimeRequired}")
    private LocalDateTime eventDateTime;

    @NotNull(message = "{addendum.validation.documentationTimeRequired}")
    private LocalDateTime documentationDateTime;

    private boolean lateEntry;

    @NotBlank(message = "{addendum.validation.pinRequired}")
    @Size(min = 6, max = 64, message = "{addendum.validation.pinRequired}")
    private String authorizingPin;
}
