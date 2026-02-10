package com.example.hms.payload.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EncounterNoteAddendumRequestDTO {

    @NotBlank
    @Size(max = 8000)
    private String content;

    private LocalDateTime eventOccurredAt;
    private LocalDateTime documentedAt;
    private Boolean attestAccuracy;
    private Boolean attestNoAbbreviations;
    private UUID authorStaffId;
    private UUID authorUserId;
    private String authorName;
    private String authorCredentials;
    private LocalDateTime signedAt;
}
