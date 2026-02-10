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

    @NotBlank
    @Size(min = 10, max = 4000)
    private String content;

    @NotNull
    private LocalDateTime eventDateTime;

    @NotNull
    private LocalDateTime documentationDateTime;

    private boolean lateEntry;

    @NotBlank
    @Size(min = 6, max = 64)
    private String authorizingPin;
}
